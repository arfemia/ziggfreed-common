package com.ziggfreed.common.objectives.producer;

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
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * Fires {@code BREAK_BLOCK} for the fallback runtime, targeted at the broken block's id.
 *
 * <p>Registered unconditionally (an ECS system is a setup-time registration), so the first line is the
 * per-KIND stand-down: a consumer that fires this same kind from its own event systems claims it,
 * and this producer returns before it does any work. That is what makes a double-fire structurally
 * impossible rather than merely avoided by convention.
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
        if (!ProgressionRuntime.defaultProducesKind(KIND) || event.isCancelled()) {
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
        ProgressDispatch.fire(store, ref, playerRef, KIND, blockId, null, 1L);
    }
}
