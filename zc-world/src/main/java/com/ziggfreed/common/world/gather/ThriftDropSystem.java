package com.ziggfreed.common.world.gather;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.EnvironmentBreakBlockEvent;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealth;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealthSection;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.ChunkSections;
import com.ziggfreed.common.world.SectionBlockCursor;
import com.ziggfreed.common.world.placed.PlacedBlockLedger;

/**
 * A block {@link ThriftSystem} spared (its placement kept the item) drops nothing when a player breaks it, so placing
 * and breaking again never makes an item from nothing. The engine's break event carries no drop of its own to trim,
 * so the step refuses the player's break of a spared block before anything else hears it (it runs first), then breaks
 * the block again in a world task through the engine's own break with the player and no drop: that break fires the
 * event afresh, so every listener (a claim mod refusing it, a block-break reward, a gather shape starting) hears one
 * break, as for any other block, and only the drop is gone. A gather shape's own breaks take a spared block's drop off
 * themselves ({@link GatherShapes}), so a shaped break passes. The spared mark goes with the block, and a mark naming
 * another block than the one now in the cell is forgotten on the spot ({@link PlacedBlockLedger#isSpared}).
 *
 * <p>Two world-level steps cover the breaks no player makes: {@link Blast} takes the drop off a spared block an
 * explosion destroys, and {@link EnvironmentBreak} forgets the mark of any block the engine breaks with no breaker.
 * A block physics breaks (a spared torch whose wall is gone) fires no event at all, so it still drops as the engine has
 * it; its mark is forgotten when the next block in the cell is placed or read.
 */
public final class ThriftDropSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES = RootDependency.firstSet();

    /** Set while this step's own bare break runs, so its event passes. */
    private static final ThreadLocal<Boolean> BARE = new ThreadLocal<>();

    public ThriftDropSystem() {
        super(BreakBlockEvent.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull BreakBlockEvent event) {
        try {
            if (event.isCancelled() || ShapedBreaks.active() || BARE.get() != null) {
                return;
            }
            BlockType type = event.getBlockType();
            Vector3i target = event.getTargetBlock();
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            UUID worldId = player == null ? null : player.getWorldUuid();
            World world = store.getExternalData().getWorld();
            if (type == null || target == null || worldId == null || world == null) {
                return;
            }
            int x = target.x;
            int y = target.y;
            int z = target.z;
            if (!PlacedBlockLedger.getInstance().isSpared(worldId, x, y, z, type)) {
                return;
            }
            event.setCancelled(true);
            String blockId = type.getId();
            world.execute(() -> breakBare(store, ref, worldId, blockId, x, y, z));
        } catch (Throwable t) {
            SafeLog.fine("[gather] a spared block's break could not be read: " + t.getMessage());
        }
    }

    /**
     * Break the spared block at the cell for the player with no drop, when it is still the block they broke and the
     * one the mark names; the mark goes once the block has. World-thread only, outside any system's tick.
     */
    static void breakBare(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull UUID worldId,
            @Nonnull String blockId, int x, int y, int z) {
        try {
            World world = store.getExternalData().getWorld();
            if (world == null || !ref.isValid()) {
                return;
            }
            PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
            SectionBlockCursor cursor = SectionBlockCursor.of(world);
            int index = BlockType.getAssetMap().getIndex(blockId);
            if (cursor.blockId(x, y, z) != index) {
                if (cursor.blockId(x, y, z) == BlockType.EMPTY_ID) {
                    ledger.clearSpared(worldId, x, y, z);
                }
                return;
            }
            BlockType type = BlockType.getAssetMap().getAsset(index);
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> section = ChunkSections.at(chunks, x, y, z);
            if (type == null || section == null || !ledger.isSpared(worldId, x, y, z, type)) {
                return;
            }
            BARE.set(Boolean.TRUE);
            try {
                BlockHarvestUtils.performBlockBreak(new Vector3i(x, y, z), type, GatherShapes.held(store, ref), 0, null,
                        null, SetBlockSettings.NONE, ref, section, store, chunks.getStore());
            } finally {
                BARE.remove();
            }
            if (cursor.blockId(x, y, z) != index) {
                ledger.clearSpared(worldId, x, y, z);
            }
        } catch (Throwable t) {
            SafeLog.warn("[gather] a spared block could not be broken: " + t.getMessage());
        }
    }

    /**
     * An explosion breaks a block through the engine's block damage with no breaker, whose world-level damage event is
     * the last point it can be refused: once that passes the drop is decided, and the break event that follows
     * ({@link EnvironmentBreakBlockEvent}) cannot be refused. So when damage with no breaker would destroy a spared
     * block, this step, which runs after every other listener has had its say (a claim mod refusing the blast), refuses
     * it and breaks the block in its place with no drop, at once, so the rest of the blast passes the cell as it would
     * have. The break goes through the engine's own break with no breaker, so every listener of an environment break
     * still hears it, and it keeps the blast's own quiet (no per-block sound or particles).
     */
    public static final class Blast extends WorldEventSystem<EntityStore, DamageBlockEvent> {

        private static final Set<Dependency<EntityStore>> LAST = RootDependency.lastSet();

        public Blast() {
            super(DamageBlockEvent.class);
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return LAST;
        }

        @Override
        public void handle(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull DamageBlockEvent event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                Vector3i target = event.getTargetBlock();
                World world = store.getExternalData().getWorld();
                if (target == null || world == null) {
                    return;
                }
                int x = target.x;
                int y = target.y;
                int z = target.z;
                // The block in the cell, as the engine's removal reads it (a listener may have moved the target).
                int id = SectionBlockCursor.of(world).blockId(x, y, z);
                BlockType type = id == BlockType.EMPTY_ID ? null : BlockType.getAssetMap().getAsset(id);
                UUID worldId = world.getWorldConfig().getUuid();
                ChunkStore chunks = world.getChunkStore();
                Ref<ChunkStore> section = type == null ? null : ChunkSections.at(chunks, x, y, z);
                if (worldId == null || section == null
                        || !PlacedBlockLedger.getInstance().isSpared(worldId, x, y, z, type)
                        || !destroys(chunks.getStore(), section, x, y, z, event)) {
                    return;
                }
                event.setCancelled(true);
                BlockHarvestUtils.performBlockBreak(new Vector3i(x, y, z), type, null, 0, null, null,
                        SetBlockSettings.NO_SEND_AUDIO | SetBlockSettings.NO_SEND_PARTICLES, null, section, commandBuffer,
                        chunks.getStore());
            } catch (Throwable t) {
                SafeLog.fine("[gather] a spared block's blast could not be read: " + t.getMessage());
            }
        }

        /** Would this damage break the block, as the engine's own block damage decides once the event passes? */
        private static boolean destroys(@Nonnull Store<ChunkStore> chunkStore, @Nonnull Ref<ChunkStore> section, int x,
                int y, int z, @Nonnull DamageBlockEvent event) {
            BlockHealthSection health = chunkStore.getComponent(section, BlockHealthSection.getComponentType());
            if (health == null) {
                return BlockHealth.isDestroyed(event.getCurrentDamage() - event.getDamage());
            }
            return health.isFragile(x, y, z) || BlockHealth.isDestroyed(health.getHealth(x, y, z) - event.getDamage());
        }
    }

    /**
     * Forgets the spared mark of every block the engine breaks with no breaker (fire burning it away, an explosion, its
     * own bare break above), the break no player's event carries: a mark left behind would otherwise cost the next
     * block standing in the cell its drop.
     */
    public static final class EnvironmentBreak extends WorldEventSystem<EntityStore, EnvironmentBreakBlockEvent> {

        public EnvironmentBreak() {
            super(EnvironmentBreakBlockEvent.class);
        }

        @Override
        public void handle(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull EnvironmentBreakBlockEvent event) {
            try {
                Vector3i target = event.getTargetBlock();
                World world = store.getExternalData().getWorld();
                UUID worldId = world == null ? null : world.getWorldConfig().getUuid();
                if (target == null || worldId == null) {
                    return;
                }
                PlacedBlockLedger.getInstance().clearSpared(worldId, target.x, target.y, target.z);
            } catch (Throwable t) {
                SafeLog.fine("[gather] an environment break's spared mark could not be cleared: " + t.getMessage());
            }
        }
    }
}
