package com.ziggfreed.common.world.gather;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.components.PlacedByInteractionComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The growth step: a farming block a player placed (a crop, a sapling, a fishing trap; the engine records its placer
 * on the block, {@code PlacedByInteractionComponent}) grows {@code GrowthSpeed} percent faster while that player is
 * in the same world. Every {@value #INTERVAL_SEC} seconds the step credits that share of the game time that passed to
 * the block's own growth clock (it moves the block's last growth tick back by it) and asks the engine to tick the
 * block now, so the engine's own farming tick grows it, through every stage rule and modifier it applies, only
 * sooner. A block already at its last stage is left alone, so nothing is banked while a crop waits to be picked.
 *
 * <p>Runs on the chunk store over farming blocks that carry a placer, on the world thread. A server without the
 * engine's farming module registers nothing ({@link #create}).
 */
public final class GrowthSpeedSystem extends EntityTickingSystem<ChunkStore> {

    /** The growth stat, read off the block's placer. */
    public static final String GROWTH_STAT = "GrowthSpeed";

    /** How often growth is credited, in seconds of server time. */
    public static final float INTERVAL_SEC = 5.0f;

    /** The most growth one credit grants, as a share of the time that passed: three times as fast at most. */
    public static final double MAX_PERCENT = 200.0;

    @Nonnull private final ComponentType<ChunkStore, FarmingBlock> farmingType;
    @Nonnull private final ComponentType<ChunkStore, PlacedByInteractionComponent> placedType;
    @Nonnull private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> stateType;
    @Nonnull private final Query<ChunkStore> query;

    /** One world's credit run: the time since the last, and the rates its placers read this run. */
    private static final class Run {
        float sinceLast;
        @Nullable Instant lastGameTime;
        @Nullable Instant now;
        long elapsedNanos;
        final Map<UUID, Double> rates = new HashMap<>();
    }

    private final Map<Store<ChunkStore>, Run> runs = Collections.synchronizedMap(new WeakHashMap<>());

    private GrowthSpeedSystem(@Nonnull ComponentType<ChunkStore, FarmingBlock> farmingType,
            @Nonnull ComponentType<ChunkStore, PlacedByInteractionComponent> placedType,
            @Nonnull ComponentType<ChunkStore, BlockModule.BlockStateInfo> stateType) {
        this.farmingType = farmingType;
        this.placedType = placedType;
        this.stateType = stateType;
        this.query = Query.and(farmingType, placedType, stateType);
    }

    /** The system, or null when the engine's farming or block components are not registered on this server. */
    @Nullable
    public static GrowthSpeedSystem create() {
        ComponentType<ChunkStore, FarmingBlock> farming = FarmingBlock.getComponentType();
        ComponentType<ChunkStore, PlacedByInteractionComponent> placed = PlacedByInteractionComponent.getComponentType();
        ComponentType<ChunkStore, BlockModule.BlockStateInfo> state = BlockModule.BlockStateInfo.getComponentType();
        if (farming == null || placed == null || state == null) {
            return null;
        }
        return new GrowthSpeedSystem(farming, placed, state);
    }

    @Nonnull
    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<ChunkStore> store) {
        Run run = runs.computeIfAbsent(store, s -> new Run());
        run.sinceLast += dt;
        if (run.sinceLast < INTERVAL_SEC) {
            return;
        }
        run.sinceLast = 0.0f;
        Instant now = gameTime(store);
        Instant last = run.lastGameTime;
        run.lastGameTime = now;
        if (now == null || last == null || !now.isAfter(last)) {
            return;
        }
        run.now = now;
        run.elapsedNanos = Duration.between(last, now).toNanos();
        run.rates.clear();
        super.tick(dt, systemIndex, store);
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        try {
            Run run = runs.get(store);
            PlacedByInteractionComponent placed = archetypeChunk.getComponent(index, placedType);
            UUID planter = placed == null ? null : placed.getWhoPlacedUuid();
            if (run == null || run.now == null || planter == null) {
                return;
            }
            double percent = run.rates.computeIfAbsent(planter, id -> rateOf(store, id));
            if (!(percent > 0.0)) {
                return;
            }
            FarmingBlock farming = archetypeChunk.getComponent(index, farmingType);
            BlockModule.BlockStateInfo state = archetypeChunk.getComponent(index, stateType);
            if (farming == null || state == null || farming.getLastTickGameTime() == null) {
                return;
            }
            Ref<ChunkStore> sectionRef = state.getSectionRef();
            BlockSection section = sectionRef == null || !sectionRef.isValid() ? null
                    : commandBuffer.getComponent(sectionRef, BlockSection.getComponentType());
            if (section == null) {
                return;
            }
            int blockIndex = state.getIndex();
            int x = ChunkUtil.xFromIndex(blockIndex);
            int y = ChunkUtil.yFromIndex(blockIndex);
            int z = ChunkUtil.zFromIndex(blockIndex);
            if (!stillGrowing(farming, section, x, y, z)) {
                return;
            }
            long credit = creditNanos(run.elapsedNanos, percent);
            if (credit <= 0L) {
                return;
            }
            farming.setLastTickGameTime(farming.getLastTickGameTime().minusNanos(credit));
            state.markNeedsSaving(commandBuffer);
            section.scheduleTick(ChunkUtil.indexBlock(x, y, z), run.now);
        } catch (Throwable t) {
            SafeLog.fine("[gather] a farming block's growth could not be credited: " + t.getMessage());
        }
    }

    /** The share of {@code elapsedNanos} that {@code percent} faster growth credits, held at {@value #MAX_PERCENT}. */
    static long creditNanos(long elapsedNanos, double percent) {
        if (elapsedNanos <= 0L || !(percent > 0.0)) {
            return 0L;
        }
        return (long) (elapsedNanos * Math.min(MAX_PERCENT, percent) / 100.0);
    }

    /** Whether the block has a stage left to grow into (never at its last stage, nor past its stages). */
    private static boolean stillGrowing(@Nonnull FarmingBlock farming, @Nonnull BlockSection section, int x, int y,
            int z) {
        String previous = farming.getPreviousBlockType();
        BlockType type = previous != null ? BlockType.getAssetMap().getAsset(previous)
                : BlockType.getAssetMap().getAsset(section.get(x, y, z));
        FarmingData data = type == null ? null : type.getFarming();
        Map<String, FarmingStageData[]> stages = data == null ? null : data.getStages();
        FarmingStageData[] set = stages == null || farming.getCurrentStageSet() == null ? null
                : stages.get(farming.getCurrentStageSet());
        return set != null && (int) farming.getGrowthProgress() < set.length - 1;
    }

    /** The placer's growth rate in percent, when they stand in this store's world; 0 otherwise. */
    private static double rateOf(@Nonnull Store<ChunkStore> store, @Nonnull UUID planter) {
        try {
            World world = store.getExternalData().getWorld();
            Ref<EntityStore> ref = PlayerAccess.liveRef(planter);
            if (world == null || ref == null) {
                return 0.0;
            }
            Store<EntityStore> entities = world.getEntityStore().getStore();
            if (ref.getStore() != entities) {
                return 0.0;
            }
            return GatherReads.max(GatherReads.stats(entities, ref), GROWTH_STAT);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** The game time of the world this chunk store belongs to, or null when it cannot be read. */
    @Nullable
    private static Instant gameTime(@Nonnull Store<ChunkStore> store) {
        try {
            World world = store.getExternalData().getWorld();
            WorldTimeResource time = world == null ? null
                    : world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType());
            return time == null ? null : time.getGameTime();
        } catch (Throwable t) {
            return null;
        }
    }
}
