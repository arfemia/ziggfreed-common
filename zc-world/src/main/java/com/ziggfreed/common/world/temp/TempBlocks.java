package com.ziggfreed.common.world.temp;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockRotation;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.BlockPlaceUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.BlockOps;
import com.ziggfreed.common.world.BuildPermission;
import com.ziggfreed.common.world.ChunkSections;
import com.ziggfreed.common.world.SectionBlockCursor;
import com.ziggfreed.common.world.placed.PlacedBlockLedger;
import com.ziggfreed.common.world.record.BlockRecordSection;

/**
 * Temporary blocks: a block put down for a player for a few seconds (a wall of cover, a step), through the engine's
 * own place path, that crumbles on its own and never hands an item back.
 *
 * <p><b>Asked like a player's own placement.</b> {@link #place} puts the block down with {@code BlockPlaceUtils
 * .placeBlock} as the player (no item in hand, nothing consumed), so the native place event fires first and a claim
 * mod or a world rule that cancels it refuses the block; before that it asks {@link BuildPermission} (the world's
 * building rule, and the environment's for a non-creative placer), and an instance world refuses unless the caller
 * allows it. It goes only into air: a cell holding anything, grass and flowers included, is skipped, so a temporary
 * block never breaks or drops what was there.
 *
 * <p><b>It gives nothing back.</b> The cell is marked in the placed-block ledger as a placement (so breaking it pays
 * no gathering credit) and as spared (so the thrift step's break hands no item back, the same mark a spared Thrift
 * placement carries). The engine's empty-hand placement is never recorded by the ledger's own recorder, which is why
 * this class writes the two marks itself.
 *
 * <p><b>It crumbles even across a restart.</b> Each block's record ({@link TempBlock}) rides its chunk section and is
 * saved with it; {@link TempBlockSystem} visits only the sections holding one and crumbles each block whose time is up
 * (wall-clock time, so a restart does not keep a wall standing). A crumble removes the block with no drop through the
 * engine's own setBlock, which plays its break particles for a block turned to air ({@code BlockOperations
 * .spawnBlockParticles}).
 *
 * <p><b>It never takes a block that is not its own.</b> A record names a block that is ours only while the cell still
 * holds that block AND the block's spared mark, which a break of it clears; the moment the cell is seen without it, the
 * record is forgotten and the cell is left alone. Two readers see it: {@link TempBlockPlaceSystem} on any placement into
 * the cell (a block can only be placed there once ours is gone, so a player's own block never inherits the record),
 * and every pass of the sweep (a break by physics, an explosion or another mod, which fires no placement). A record
 * that comes back with its chunk after a restart or an unload is read the same way before anything is removed.
 *
 * <p><b>World-thread only, outside any system's tick</b> (the place event and the block writes): a caller inside an
 * interaction or a system defers onto {@code world.execute}.
 */
public final class TempBlocks {

    /** The id the record component registers under; also its key in a saved chunk. */
    public static final String REGISTRY_ID = "ZigTempBlock";

    /** Why a cell got no temporary block, or that it did. */
    public enum Outcome {
        /** The block is down, and will crumble. */
        PLACED,
        /** The cell holds something already: only air takes a temporary block. */
        NOT_AIR,
        /** The world or its environment there does not allow building ({@link BuildPermission}). */
        NOT_ALLOWED,
        /** An instance world, and the caller did not allow one. */
        INSTANCE,
        /** The native place event was cancelled (a claim, a rule) or the engine refused the placement. */
        REFUSED,
        /** The cell's chunk section is not in memory. */
        UNLOADED,
        /** Something could not be read (no world, an unknown block, no placer). */
        FAILED
    }

    @Nullable
    private static volatile BlockRecordSection.Handle<TempBlock> handle;

    private TempBlocks() {
    }

    /**
     * Register the record component on the chunk-store registry. Call once at plugin setup, BEFORE any world loads
     * (the library's own bootstrap does). Never throws.
     */
    public static void register(@Nonnull ComponentRegistryProxy<ChunkStore> registry) {
        handle = BlockRecordSection.register(registry, REGISTRY_ID, TempBlock.CODEC);
    }

    /** The record component's type, for the crumble sweep's query; null when registration failed. */
    @Nullable
    static ComponentType<ChunkStore, BlockRecordSection<TempBlock>> componentType() {
        BlockRecordSection.Handle<TempBlock> h = handle;
        return h != null ? h.componentType() : null;
    }

    /** Is {@code world} an instance (a dungeon, an event run)? False for no world. */
    public static boolean isInstance(@Nullable World world) {
        if (world == null) {
            return false;
        }
        try {
            return InstanceWorldConfig.get(world.getWorldConfig()) != null;
        } catch (Throwable t) {
            SafeLog.fine("[temp] could not tell whether a world is an instance: " + t.getMessage());
            return false;
        }
    }

    /**
     * Put a temporary {@code blockTypeKey} block at the cell for {@code placer}, crumbling {@code lifeMs} from now. See
     * the class javadoc for what is asked first. {@code entities} is the placer's entity store accessor (the live
     * store, outside a system's tick).
     */
    @Nonnull
    public static Outcome place(@Nullable World world, @Nonnull Ref<EntityStore> placer,
            @Nonnull ComponentAccessor<EntityStore> entities, int x, int y, int z, @Nonnull String blockTypeKey,
            long lifeMs, boolean allowInInstance) {
        BlockRecordSection.Handle<TempBlock> h = handle;
        if (world == null || h == null || !h.isRegistered() || !placer.isValid()) {
            return Outcome.FAILED;
        }
        try {
            if (!allowInInstance && isInstance(world)) {
                return Outcome.INSTANCE;
            }
            int typeIndex = BlockType.getAssetMap().getIndex(blockTypeKey);
            BlockType type = typeIndex == Integer.MIN_VALUE ? null : BlockType.getAssetMap().getAsset(typeIndex);
            if (type == null) {
                return Outcome.FAILED;
            }
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> section = ChunkSections.at(chunks, x, y, z);
            if (section == null || !section.isValid()) {
                return Outcome.UNLOADED;
            }
            SectionBlockCursor cursor = SectionBlockCursor.of(world);
            if (cursor.blockId(x, y, z) != BlockType.EMPTY_ID) {
                return Outcome.NOT_AIR;
            }
            if (!BuildPermission.allowsPlacement(world, gameModeOf(entities, placer), x, y, z)) {
                return Outcome.NOT_ALLOWED;
            }
            InventoryComponent.Hotbar hotbar = entities.getComponent(placer, InventoryComponent.Hotbar.getComponentType());
            ItemContainer container = hotbar != null ? hotbar.getInventory() : null;
            if (container == null) {
                return Outcome.FAILED;
            }
            Store<ChunkStore> chunkStore = chunks.getStore();
            boolean placed = BlockPlaceUtils.placeBlock(placer, null, blockTypeKey, container, new Vector3i(0, 1, 0),
                    new Vector3i(x, y, z), new BlockRotation(), (byte) 0, false, section, chunkStore, entities, false,
                    false, false);
            if (!placed || SectionBlockCursor.of(world).blockId(x, y, z) != typeIndex) {
                return Outcome.REFUSED;
            }
            UUID placerId = uuidOf(entities, placer);
            UUID worldId = world.getWorldConfig().getUuid();
            PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
            if (placerId != null) {
                ledger.trackPlacement(placerId, worldId, x, y, z);
            }
            ledger.markSpared(worldId, x, y, z, type);
            TempBlock record = h.ensureAndGet(chunkStore, section, x, y, z);
            if (record != null) {
                record.set(blockTypeKey, System.currentTimeMillis() + Math.max(0L, lifeMs),
                        placerId != null ? placerId.toString() : null, x, y, z);
                h.markDirty(chunkStore, section);
            }
            return Outcome.PLACED;
        } catch (Throwable t) {
            SafeLog.warn("[temp] a temporary block at (" + x + ", " + y + ", " + z + ") could not be placed: "
                    + t.getMessage());
            return Outcome.FAILED;
        }
    }

    /** Is there a temporary block recorded at this cell? False for an unloaded section. */
    public static boolean isTemporary(@Nullable World world, int x, int y, int z) {
        BlockRecordSection.Handle<TempBlock> h = handle;
        if (world == null || h == null) {
            return false;
        }
        try {
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> section = ChunkSections.at(chunks, x, y, z);
            return section != null && h.get(chunks.getStore(), section, x, y, z) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Crumble the temporary block at this cell now: removed with no drop (the engine's break particles play) while it
     * is still ours (see the class javadoc), then its record and its ledger marks go. A record whose block is no longer
     * ours is forgotten and the cell left alone; a removal that fails keeps the record, so the next sweep tries again.
     * True when a block was removed. World-thread only, outside any system's tick.
     */
    public static boolean crumble(@Nullable World world, int x, int y, int z) {
        return visit(world, x, y, z, Long.MAX_VALUE);
    }

    /**
     * The sweep's read of one recorded cell at {@code nowMs}: a record whose block is no longer ours is forgotten, and
     * one that is due crumbles. True when a block was removed. World-thread only, outside any system's tick.
     */
    static boolean sweep(@Nonnull World world, int x, int y, int z, long nowMs) {
        return visit(world, x, y, z, nowMs);
    }

    /**
     * A placement is about to go into this cell: a record there whose block is no longer ours is forgotten now, before
     * the new block lands, so a block a player puts in the cell never inherits it. Never removes a block.
     */
    static void onPlacementInto(@Nullable World world, int x, int y, int z) {
        BlockRecordSection.Handle<TempBlock> h = handle;
        if (world == null || h == null) {
            return;
        }
        try {
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> section = ChunkSections.at(chunks, x, y, z);
            if (section == null || !section.isValid()) {
                return;
            }
            Store<ChunkStore> chunkStore = chunks.getStore();
            TempBlock record = h.get(chunkStore, section, x, y, z);
            if (record != null && ownedType(world, record, x, y, z) == null) {
                h.remove(chunkStore, section, x, y, z);
            }
        } catch (Throwable t) {
            SafeLog.fine("[temp] a placement into a temporary block's cell could not be read: " + t.getMessage());
        }
    }

    /**
     * Read the record at the cell: forget it when its block is no longer ours, crumble it when due at {@code nowMs}
     * ({@link Long#MAX_VALUE} for now, whatever its time).
     */
    private static boolean visit(@Nullable World world, int x, int y, int z, long nowMs) {
        BlockRecordSection.Handle<TempBlock> h = handle;
        if (world == null || h == null) {
            return false;
        }
        try {
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> section = ChunkSections.at(chunks, x, y, z);
            if (section == null || !section.isValid()) {
                return false;
            }
            Store<ChunkStore> chunkStore = chunks.getStore();
            TempBlock record = h.get(chunkStore, section, x, y, z);
            if (record == null) {
                return false;
            }
            BlockType owned = ownedType(world, record, x, y, z);
            if (owned == null) {
                h.remove(chunkStore, section, x, y, z);
                return false;
            }
            if (nowMs != Long.MAX_VALUE && !record.dueAt(nowMs)) {
                return false;
            }
            boolean removed = BlockOps.setBlock(chunks, x, y, z, BlockType.EMPTY_KEY);
            if (!removed && SectionBlockCursor.of(world).blockId(x, y, z) != BlockType.EMPTY_ID) {
                SafeLog.fine("[temp] a temporary block at (" + x + ", " + y + ", " + z
                        + ") did not crumble; the next sweep tries again");
                return false;
            }
            h.remove(chunkStore, section, x, y, z);
            UUID worldId = world.getWorldConfig().getUuid();
            PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
            ledger.clearSpared(worldId, x, y, z);
            String placer = record.getPlacer();
            ledger.consumePlacement(placer != null ? UUID.fromString(placer) : new UUID(0L, 0L), worldId, x, y, z);
            return removed;
        } catch (Throwable t) {
            SafeLog.warn("[temp] a temporary block at (" + x + ", " + y + ", " + z + ") could not crumble: "
                    + t.getMessage());
            return false;
        }
    }

    /**
     * The recorded block's type while the cell still holds it with its spared mark (the block is still ours), else
     * null: a break clears the mark, so a block of the same kind put in the cell since never reads as ours.
     */
    @Nullable
    private static BlockType ownedType(@Nonnull World world, @Nonnull TempBlock record, int x, int y, int z) {
        String block = record.getBlock();
        int typeIndex = block == null ? Integer.MIN_VALUE : BlockType.getAssetMap().getIndex(block);
        BlockType type = typeIndex == Integer.MIN_VALUE ? null : BlockType.getAssetMap().getAsset(typeIndex);
        if (type == null || SectionBlockCursor.of(world).blockId(x, y, z) != typeIndex) {
            return null;
        }
        UUID worldId = world.getWorldConfig().getUuid();
        return PlacedBlockLedger.getInstance().isSpared(worldId, x, y, z, type) ? type : null;
    }

    @Nullable
    private static GameMode gameModeOf(@Nonnull ComponentAccessor<EntityStore> entities, @Nonnull Ref<EntityStore> ref) {
        try {
            Player player = entities.getComponent(ref, Player.getComponentType());
            return player != null ? player.getGameMode() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static UUID uuidOf(@Nonnull ComponentAccessor<EntityStore> entities, @Nonnull Ref<EntityStore> ref) {
        try {
            UUIDComponent id = entities.getComponent(ref, UUIDComponent.getComponentType());
            return id != null ? id.getUuid() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
