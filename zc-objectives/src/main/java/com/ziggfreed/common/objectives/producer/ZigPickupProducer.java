package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * Fires {@code PICKUP_ITEM} for the fallback runtime, targeted at the picked-up item's id.
 *
 * <p>One event is one pickup, so the amount is always one.
 */
public final class ZigPickupProducer extends EntityEventSystem<EntityStore, InteractivelyPickupItemEvent> {

    /** The objective kind this producer feeds. */
    public static final String KIND = "PICKUP_ITEM";

    public ZigPickupProducer() {
        super(InteractivelyPickupItemEvent.class);
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
            @Nonnull final InteractivelyPickupItemEvent event) {
        if (!ProgressionRuntime.defaultProducesKind(KIND)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack == null) {
            return;
        }
        String itemId = stack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        ProgressDispatch.fire(store, ref, playerRef, KIND, itemId, null, 1L);
    }
}
