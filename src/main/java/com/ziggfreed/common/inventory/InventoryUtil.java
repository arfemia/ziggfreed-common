package com.ziggfreed.common.inventory;

import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Mod-agnostic helpers for reading and mutating an entity's inventory by item id,
 * across ALL inventory sections at once (Armor / Hotbar / Storage / Utility / Tool /
 * Backpack) via {@link InventoryComponent#getCombined}. The single seam a minigame
 * uses to grant, count, and spend a custom resource item (e.g. a gathered charge)
 * without re-deriving the combined-container plumbing per mod.
 *
 * <p><b>World thread only.</b> Every method touches the {@link Store} and so must run
 * on the entity's world thread (a system tick or inside {@code world.execute}). Each
 * call is fully try-guarded: a missing component, an invalid ref, or any engine throw
 * degrades to a no-op return (0 / {@code false}), never an exception into the caller.
 */
public final class InventoryUtil {

    private InventoryUtil() {
    }

    /**
     * Total quantity of {@code itemId} the entity holds across every inventory section.
     *
     * @return the summed count, or 0 if the ref is invalid / has no inventory / on any error
     */
    public static int count(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                            @Nonnull String itemId) {
        if (!ref.isValid()) {
            return 0;
        }
        try {
            CombinedItemContainer inv = combined(store, ref);
            if (inv == null) {
                return 0;
            }
            return inv.countItemStacks(byId(itemId));
        } catch (Throwable t) {
            warn("count", itemId, t);
            return 0;
        }
    }

    /**
     * Whether the entity holds at least {@code n} of {@code itemId}.
     */
    public static boolean has(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                              @Nonnull String itemId, int n) {
        return n <= 0 || count(store, ref, itemId) >= n;
    }

    /**
     * Give {@code n} of {@code itemId} to the entity (added across the combined sections,
     * filling existing stacks first).
     *
     * @return how many did NOT fit (0 = all delivered); also 0 on a no-op / error path
     */
    public static int give(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                           @Nonnull String itemId, int n) {
        if (!ref.isValid() || n <= 0) {
            return 0;
        }
        try {
            CombinedItemContainer inv = combined(store, ref);
            if (inv == null) {
                return n;
            }
            ItemStackTransaction txn = inv.addItemStack(new ItemStack(itemId, n));
            ItemStack remainder = txn == null ? null : txn.getRemainder();
            return remainder == null ? 0 : Math.max(0, remainder.getQuantity());
        } catch (Throwable t) {
            warn("give", itemId, t);
            return n;
        }
    }

    /**
     * Take (remove) up to {@code n} of {@code itemId} from the entity.
     *
     * @return how many were ACTUALLY removed (0 if none held / error); never more than {@code n}
     */
    public static int take(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                           @Nonnull String itemId, int n) {
        if (!ref.isValid() || n <= 0) {
            return 0;
        }
        try {
            CombinedItemContainer inv = combined(store, ref);
            if (inv == null) {
                return 0;
            }
            ItemStackTransaction txn = inv.removeItemStack(new ItemStack(itemId, n));
            ItemStack remainder = txn == null ? null : txn.getRemainder();
            int notRemoved = remainder == null ? 0 : Math.max(0, remainder.getQuantity());
            return Math.max(0, n - notRemoved);
        } catch (Throwable t) {
            warn("take", itemId, t);
            return 0;
        }
    }

    /**
     * Try to spend EXACTLY {@code n} of {@code itemId}: removes them only if the entity
     * holds at least {@code n}, so a partial spend never happens.
     *
     * @return {@code true} if all {@code n} were removed; {@code false} if the entity held
     *         fewer than {@code n} (nothing removed) or on any error
     */
    public static boolean spend(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                @Nonnull String itemId, int n) {
        if (n <= 0) {
            return true;
        }
        if (!has(store, ref, itemId, n)) {
            return false;
        }
        return take(store, ref, itemId, n) >= n;
    }

    private static CombinedItemContainer combined(@Nonnull Store<EntityStore> store,
                                                  @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING);
    }

    @Nonnull
    private static Predicate<ItemStack> byId(@Nonnull String itemId) {
        return stack -> stack != null && itemId.equals(stack.getItemId());
    }

    private static void warn(@Nonnull String op, @Nonnull String itemId, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log(
                    "[ZiggfreedCommon] InventoryUtil." + op + "(" + itemId + ") failed: " + t.getMessage());
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
