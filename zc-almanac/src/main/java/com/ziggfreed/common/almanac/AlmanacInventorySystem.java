package com.ziggfreed.common.almanac;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * The owned marks' trigger: an item a season page's collection lists landing in any of a player's inventory
 * sections, by any route (a pickup, a drop walked over, a craft at hand or at a bench, a reward, a purchase, a
 * chest, a trade). The engine fires one {@link InventoryChangeEvent} per container change; this walks the changed
 * container and marks each listed item it holds. It is zc-almanac's one ECS system: an item reaching a bag is no
 * shared moment, so the moment counter cannot see it. Off means absent: an Almanac switched off marks nothing.
 *
 * <p>It never calls {@link ServerTallies}, never refuses or edits the change, and never adds a component: a
 * player with no Almanac record yet is simply not marked.
 */
public final class AlmanacInventorySystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    public AlmanacInventorySystem() {
        super(InventoryChangeEvent.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> chunk,
            @Nonnull final Store<EntityStore> store, @Nonnull final CommandBuffer<EntityStore> buffer,
            @Nonnull final InventoryChangeEvent event) {
        try {
            AlmanacIndex tracked = AlmanacEntryConfig.getInstance().index();
            ComponentType<EntityStore, AlmanacComponent> type = AlmanacComponent.TYPE;
            if (!AlmanacSwitch.isOn() || tracked.trackedItems().isEmpty() || type == null) {
                return;
            }
            AlmanacComponent record = store.getComponent(chunk.getReferenceTo(index), type);
            ItemContainer container = event.getItemContainer();
            if (record == null || record.tallies == null || container == null) {
                return;
            }
            short capacity = container.getCapacity();
            for (short slot = 0; slot < capacity; slot++) {
                ItemStack stack = container.getItemStack(slot);
                if (!ItemStack.isEmpty(stack)) {
                    AlmanacCollection.markIfTracked(record.tallies, tracked, stack.getItemId());
                }
            }
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                SafeLog.warn("[almanac] an inventory change could not be read for the owned marks: " + t.getMessage());
            }
        }
    }
}
