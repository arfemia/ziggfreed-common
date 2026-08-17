package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.placed.PlacedBlockRecorder;

/**
 * Fires {@code PLACE_BLOCK} for the fallback runtime, targeted at the placed item's id.
 *
 * <p><b>This producer always runs.</b> There is no claim and no stand-down: nothing may register a
 * competing producer for the same native event, so a placement is dispatched here exactly once.
 *
 * <p>It counts exactly the placements the shared {@link PlacedBlockRecorder} records, through the
 * recorder's own {@link PlacedBlockRecorder#placementCounts predicate}: a cancelled placement never
 * happened, an empty hand placed nothing, and a creative-mode placement is exempt - the same three
 * filters, read once, so what is remembered as "placed" and what is produced as a moment can never
 * disagree. The moment carries a {@link PlaceBlockPayload}.
 */
public final class ZigPlaceBlockProducer extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    /** The objective kind this producer feeds. */
    public static final String KIND = "PLACE_BLOCK";

    public ZigPlaceBlockProducer() {
        super(PlaceBlockEvent.class);
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
            @Nonnull final PlaceBlockEvent event) {
        try {
            var placed = event.getItemInHand();
            String itemId = placed == null ? null : placed.getItemId();
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            Player player = store.getComponent(ref, Player.getComponentType());
            if (!PlacedBlockRecorder.placementCounts(event.isCancelled(), itemId,
                    player == null ? null : player.getGameMode())) {
                return;
            }
            ProgressDispatch.fire(store, ref, commandBuffer, KIND, itemId, null, 1L,
                    new PlaceBlockPayload(event));
        } catch (Throwable t) {
            SafeLog.warn("[progression] place-block dispatch failed", t);
        }
    }
}
