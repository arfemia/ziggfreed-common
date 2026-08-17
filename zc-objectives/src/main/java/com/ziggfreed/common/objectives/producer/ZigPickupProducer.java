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
import com.ziggfreed.common.world.placed.PlacedBlockLedger;

/**
 * Fires {@code PICKUP_ITEM} for the fallback runtime, targeted at the picked-up item's id.
 *
 * <p><b>This producer always runs.</b> There is no claim and no stand-down: nothing may register a
 * competing producer for the same native event, so a pickup is dispatched here exactly once.
 *
 * <p>An item the player put down themselves is NOT credited: the shared {@link PlacedBlockLedger}
 * is asked first, and it is the same ledger every consumer's own XP path reads, so progress and XP
 * can never disagree about whether an item was placed.
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
        // A cancelled pickup never happened: it credits nothing, and it must not spend a remembered
        // copy either, or the placed item the player did not actually pick up would be forgotten and
        // the NEXT pickup of it would count.
        if (event.isCancelled()) {
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
        // The stack instance IS the moment: every system handling this one pickup is handed the
        // same object, so the ledger can tell a second reader apart from a second pickup and spend
        // exactly one remembered copy either way.
        if (PlacedBlockLedger.getInstance().consumePlacedItem(playerRef.getUuid(), itemId,
                System.identityHashCode(stack))) {
            return;
        }
        ProgressDispatch.fire(store, ref, KIND, itemId, null, 1L);
    }
}
