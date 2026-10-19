package com.ziggfreed.common.usegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.ArmorSlotAddFilter;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code Worn} axis: a player's own armor slots refuse a piece a use gate refuses them, through the engine's
 * own per-slot ADD filter, so the piece never goes on (it stays where it was) and no stat of it ever applies. Each
 * of the five typed armor slots keeps the engine's slot-type rule ({@link ArmorSlotAddFilter}) and adds the gate
 * after it; a piece already worn is never taken off, and only an ADD is ever asked.
 *
 * <p>The engine sets its armor filters again whenever the armor container loads, so the library installs these at
 * every player-ready, on the player's world thread ({@link #onPlayerReady}). Until then, and for an entity with no
 * armor container, nothing is gated. A filter whose player has gone answers as the engine's rule alone.
 */
public final class UseGateWornFilter implements SlotFilter {

    /** The typed armor slots the engine gives a slot-type filter of its own. */
    private static final int TYPED_SLOTS = 5;

    @Nonnull private final SlotFilter engine;
    @Nonnull private final Ref<EntityStore> ref;

    private UseGateWornFilter(@Nonnull SlotFilter engine, @Nonnull Ref<EntityStore> ref) {
        this.engine = engine;
        this.ref = ref;
    }

    @Override
    public boolean test(FilterActionType actionType, ItemContainer container, short slot, @Nullable ItemStack itemStack) {
        if (!engine.test(actionType, container, slot, itemStack)) {
            return false;
        }
        if (actionType != FilterActionType.ADD || itemStack == null || itemStack.isEmpty() || !ref.isValid()
                || UseGates.nothingToAsk(UseGateAxis.WORN)) {
            return true;
        }
        try {
            Store<EntityStore> store = ref.getStore();
            UseGateRefusal refusal = UseGates.itemRefusal(store, ref, UseGateAxis.WORN, itemStack, null);
            if (refusal == null) {
                return true;
            }
            UseGates.announce(store, ref, refusal);
            return false;
        } catch (Throwable t) {
            SafeLog.fine("[usegate] an armor slot could not ask its gate: " + t.getMessage());
            return true;
        }
    }

    /** The player is ready in a world: hang the worn gate on their armor slots, on that world's thread. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> install(ref));
        } catch (Throwable t) {
            SafeLog.warn("[usegate] the armor slots could not be gated at player ready: " + t.getMessage());
        }
    }

    /** Put the worn gate on each typed armor slot of {@code ref}'s armor container. World thread. */
    static void install(@Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            ItemContainer container = armor == null ? null : armor.getInventory();
            if (!(container instanceof SimpleItemContainer slots)) {
                return;
            }
            ItemArmorSlot[] types = ItemArmorSlot.VALUES;
            int typed = Math.min(Math.min(TYPED_SLOTS, types.length), slots.getCapacity());
            for (short slot = 0; slot < typed; slot++) {
                slots.setSlotFilter(FilterActionType.ADD, slot,
                        new UseGateWornFilter(new ArmorSlotAddFilter(types[slot]), ref));
            }
        } catch (Throwable t) {
            SafeLog.warn("[usegate] the armor slots could not be gated: " + t.getMessage());
        }
    }
}
