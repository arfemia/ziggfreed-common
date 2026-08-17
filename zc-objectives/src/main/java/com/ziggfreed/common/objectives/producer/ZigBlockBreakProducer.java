package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.placed.PlacedBlockLedger;

/**
 * Fires {@code BREAK_BLOCK} for the fallback runtime, targeted at the broken block's id.
 *
 * <p><b>This producer always runs.</b> There is no claim and no stand-down: nothing may register a
 * competing producer for the same native event, so a break is dispatched here exactly once and a
 * mod wanting a moment nobody covers calls {@link ProgressDispatch#fire} from its own event system
 * instead.
 *
 * <p>A block the breaker put down themselves is NOT credited: the shared
 * {@link PlacedBlockLedger} is asked first, and it is the same ledger every consumer's own XP path
 * reads, so progress and XP can never disagree about whether a block was placed.
 */
public final class ZigBlockBreakProducer extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    /** The objective kind this producer feeds. */
    public static final String KIND = "BREAK_BLOCK";

    /** The engine's id for "there is nothing here", which is not a block anybody broke. */
    private static final String EMPTY_BLOCK_ID = "Empty";

    public ZigBlockBreakProducer() {
        super(BreakBlockEvent.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull final Store<EntityStore> store,
            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
            @Nonnull final BreakBlockEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            String blockId = event.getBlockType().getId();
            if (blockId == null || blockId.isBlank() || EMPTY_BLOCK_ID.equals(blockId)) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            if (placedByBreaker(playerRef, event)) {
                return;
            }
            ProgressDispatch.fire(store, ref, playerRef, KIND, blockId, null, 1L);
        } catch (Throwable t) {
            SafeLog.warn("[progression] block-break dispatch failed", t);
        }
    }

    /**
     * Did this breaker put this block down themselves? A player with no world resolved is answered
     * NOT PLACED: the ledger keys a placement by world, so with no world there is no row that could
     * name this position, and "no row" has always meant an ordinary break that credits normally.
     * Guarded here rather than inside the ledger, whose whole surface is keyed on a real world.
     */
    private static boolean placedByBreaker(@Nonnull PlayerRef playerRef,
            @Nonnull BreakBlockEvent event) {
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid == null) {
            return false;
        }
        var position = event.getTargetBlock();
        return PlacedBlockLedger.getInstance().consumePlacement(playerRef.getUuid(), worldUuid,
                position.x, position.y, position.z);
    }
}
