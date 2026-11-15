package com.ziggfreed.common.objectives.producer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ClearTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code CONSUME_ITEM} when a player eats or drinks, Target the item's id, Amount 1: the one producer of the
 * kind, so a meal counts once for every reader of it.
 *
 * <p>The engine has no consume event, so eating is read off {@link InventoryChangeEvent} through a narrow chain:
 * the hotbar, a succeeded plain {@link ActionType#REMOVE} on the ACTIVE slot taking exactly one, of an item that
 * {@link Item#isConsumable() is consumable}. A move or a clear, and any change that also adds (a stack reshuffle),
 * is not one, and nor is a removal within {@value #CRAFT_VETO_MS} ms of the player's own craft
 * ({@link #markCraft}, from {@link ZigCraftProducer}), which draws its ingredients from the same slots.
 *
 * <p><b>A drop is not a meal.</b> The engine announces a player's drop ({@link DropItemEvent.PlayerRequest}, naming
 * the section and the slot) before it takes the item out, so {@link DropRequest} marks that hotbar slot and the
 * next removal from it within {@value #DROP_VETO_MS} ms is the drop, never counted; the mark is spent there, so a
 * meal right after still counts. Without it, dropping one consumable and picking it up again would farm a step.
 */
public final class ZigConsumeItemProducer extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    /** The objective kind this producer feeds. */
    public static final String KIND = "CONSUME_ITEM";

    /** How long after a craft a removal is the craft's ingredient rather than a meal. */
    static final long CRAFT_VETO_MS = 250L;

    /** How long after a drop request a removal from that slot is the drop rather than a meal. */
    static final long DROP_VETO_MS = 2000L;

    /** Per player, when they last crafted. Transient and per process, dropped when they leave. */
    private static final Map<UUID, Long> LAST_CRAFT = new ConcurrentHashMap<>();

    /** Per player, the hotbar slot they last asked to drop from and when. Transient and per process. */
    private static final Map<UUID, Drop> LAST_DROP = new ConcurrentHashMap<>();

    /** One drop request: the hotbar slot and when it was asked. */
    record Drop(int slot, long at) {
    }

    public ZigConsumeItemProducer() {
        super(InventoryChangeEvent.class);
    }

    /** Register the producer and the departure cleanup. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new ZigConsumeItemProducer());
        plugin.getEntityStoreRegistry().registerSystem(new DropRequest());
        plugin.getEventRegistry().register(PlayerDisconnectEvent.class, ZigConsumeItemProducer::onDisconnect);
    }

    /** Marks the hotbar slot a player asks to drop from, ahead of the removal it causes. */
    public static final class DropRequest extends EntityEventSystem<EntityStore, DropItemEvent.PlayerRequest> {

        public DropRequest() {
            super(DropItemEvent.PlayerRequest.class);
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull DropItemEvent.PlayerRequest event) {
            try {
                // A cancelled request removes nothing (the engine pre-cancels it in a game mode that
                // prevents drops, and another mod may cancel it), so it must not veto the next real meal.
                if (event.isCancelled() || event.getInventorySectionId() != InventoryComponent.HOTBAR_SECTION_ID) {
                    return;
                }
                PlayerRef player = PlayerAccess.playerRef(store, chunk.getReferenceTo(index));
                if (player != null) {
                    markDrop(player.getUuid(), event.getSlotId(), System.currentTimeMillis());
                }
            } catch (Throwable t) {
                SafeLog.fine("[progression] a drop request could not be marked: " + t.getMessage());
            }
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
        try {
            Meal meal = consumedItem(event);
            if (meal == null) {
                return;
            }
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef player = PlayerAccess.playerRef(store, ref);
            if (player == null || craftedJustNow(player.getUuid())
                    || droppedJustNow(player.getUuid(), meal.slot(), System.currentTimeMillis())) {
                return;
            }
            ProgressDispatch.fire(store, ref, commandBuffer, KIND, meal.itemId(), null, 1L, null);
        } catch (Throwable t) {
            SafeLog.warn("[progression] consume-item dispatch failed", t);
        }
    }

    /** One consumable taken off the active hotbar slot: its id and the slot. */
    record Meal(@Nonnull String itemId, int slot) {
    }

    /** The one consumable the change took off the active hotbar slot, or null when it took none. */
    @Nullable
    private static Meal consumedItem(@Nonnull InventoryChangeEvent event) {
        if (!(event.getInventory() instanceof InventoryComponent.Hotbar hotbar)) {
            return null;
        }
        byte active = hotbar.getActiveSlot();
        Transaction tx = event.getTransaction();
        if (active < 0 || tx == null || !tx.succeeded() || tx instanceof MoveTransaction
                || tx instanceof ClearTransaction || containsAdd(tx)) {
            return null;
        }
        SlotTransaction removed = findActiveSlotRemove(tx, active);
        ItemStack before = removed == null ? null : removed.getSlotBefore();
        if (before == null) {
            return null;
        }
        int afterQty = removed.getSlotAfter() != null ? removed.getSlotAfter().getQuantity() : 0;
        if (before.getQuantity() - afterQty != 1) {
            return null;
        }
        Item item = before.getItem();
        return item != null && item.isConsumable() ? new Meal(before.getItemId(), active) : null;
    }

    /** Depth-first walk for a succeeded plain REMOVE on the active hotbar slot. */
    @Nullable
    private static SlotTransaction findActiveSlotRemove(@Nonnull Transaction tx, byte active) {
        if (tx instanceof SlotTransaction slot) {
            return removesFrom(slot, active) ? slot : null;
        }
        if (tx instanceof ItemStackTransaction stack) {
            for (SlotTransaction slot : stack.getSlotTransactions()) {
                if (slot != null && removesFrom(slot, active)) {
                    return slot;
                }
            }
            return null;
        }
        if (tx instanceof ListTransaction<?> list) {
            for (Object child : list.getList()) {
                if (child instanceof Transaction childTx) {
                    SlotTransaction found = findActiveSlotRemove(childTx, active);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private static boolean removesFrom(@Nonnull SlotTransaction slot, byte active) {
        return slot.succeeded() && slot.getAction() == ActionType.REMOVE && slot.getSlot() == active;
    }

    /** True when any slot change in the tree ADDS (a move or a reshuffle, never a meal). */
    private static boolean containsAdd(@Nonnull Transaction tx) {
        if (tx instanceof SlotTransaction slot) {
            return slot.succeeded() && slot.getAction().isAdd();
        }
        if (tx instanceof ItemStackTransaction stack) {
            for (SlotTransaction slot : stack.getSlotTransactions()) {
                if (slot != null && slot.succeeded() && slot.getAction().isAdd()) {
                    return true;
                }
            }
            return false;
        }
        if (tx instanceof ListTransaction<?> list) {
            for (Object child : list.getList()) {
                if (child instanceof Transaction childTx && containsAdd(childTx)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The player just crafted: a removal in the next {@value #CRAFT_VETO_MS} ms is an ingredient, not a meal. */
    static void markCraft(@Nullable UUID playerId) {
        if (playerId != null) {
            LAST_CRAFT.put(playerId, System.currentTimeMillis());
        }
    }

    private static boolean craftedJustNow(@Nullable UUID playerId) {
        Long last = playerId == null ? null : LAST_CRAFT.get(playerId);
        return last != null && System.currentTimeMillis() - last < CRAFT_VETO_MS;
    }

    /** The player asked, at {@code now}, to drop from hotbar {@code slot}: the next removal from it is that drop. */
    static void markDrop(@Nullable UUID playerId, int slot, long now) {
        if (playerId != null) {
            LAST_DROP.put(playerId, new Drop(slot, now));
        }
    }

    /**
     * Is this removal from {@code slot} the player's own drop? True, and the mark spent, when they asked to drop from
     * that slot within {@value #DROP_VETO_MS} ms of {@code now}.
     */
    static boolean droppedJustNow(@Nullable UUID playerId, int slot, long now) {
        Drop drop = playerId == null ? null : LAST_DROP.get(playerId);
        if (drop == null || drop.slot() != slot || now - drop.at() >= DROP_VETO_MS) {
            return false;
        }
        LAST_DROP.remove(playerId, drop);
        return true;
    }

    /** A player left the server: their last craft and drop go. */
    static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            if (player != null && player.getUuid() != null) {
                LAST_CRAFT.remove(player.getUuid());
                LAST_DROP.remove(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[progression] a departing player's last craft could not be dropped: " + t.getMessage());
        }
    }
}
