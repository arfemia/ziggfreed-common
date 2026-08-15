package com.ziggfreed.common.inventory;

import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;

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
 *
 * <p><b>Every read and write also has a SECTION-SCOPED form.</b> The combined view is the right
 * default for a resource a player simply carries, and the wrong one for anything that decides what
 * they can be CHARGED: a price counting what somebody is wearing will happily strip it off their
 * body, and a balance that includes their armor is not a balance they can spend. A caller that
 * cares names the sections itself with {@link #spendableSections()} (or its own set) and gets the
 * same behaviour narrowed to them.
 */
public final class InventoryUtil {

    private InventoryUtil() {
    }

    /**
     * The sections a player moves things in and out of freely: backpack, storage, hotbar.
     *
     * <p>What it deliberately leaves out is what somebody is WEARING and what is in their utility
     * slots. Those are worn equipment rather than carried goods, so a price that counted them would
     * both read as affordable off the strength of a helmet and then take the helmet.
     *
     * <p>It is a method rather than a constant because the engine fills the underlying arrays during
     * its own bootstrap, so a field initialised at class-load could capture nothing.
     */
    @Nonnull
    public static ComponentType<EntityStore, ? extends InventoryComponent>[] spendableSections() {
        return InventoryComponent.BACKPACK_STORAGE_HOTBAR;
    }

    /**
     * Total quantity of {@code itemId} the entity holds across every inventory section.
     *
     * @return the summed count, or 0 if the ref is invalid / has no inventory / on any error
     */
    public static int count(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                            @Nonnull String itemId) {
        return count(store, ref, itemId, InventoryComponent.EVERYTHING);
    }

    /** {@link #count} narrowed to {@code sections}, e.g. {@link #spendableSections()}. */
    @SafeVarargs
    public static int count(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                            @Nonnull String itemId,
                            @Nonnull ComponentType<EntityStore, ? extends InventoryComponent>... sections) {
        if (!ref.isValid()) {
            return 0;
        }
        try {
            CombinedItemContainer inv = InventoryComponent.getCombined(store, ref, sections);
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

    /** {@link #has} narrowed to {@code sections}. */
    @SafeVarargs
    public static boolean has(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                              @Nonnull String itemId, int n,
                              @Nonnull ComponentType<EntityStore, ? extends InventoryComponent>... sections) {
        return n <= 0 || count(store, ref, itemId, sections) >= n;
    }

    /**
     * Whether {@code n} of {@code itemId} would fit in the entity's combined inventory
     * RIGHT NOW (a non-mutating space check, the basis of the "no claiming with a full
     * inventory" guard). Backed by {@code CombinedItemContainer.canAddItemStacks}.
     *
     * @return true if it all fits; false if the ref is invalid / no inventory / it would
     *         not all fit / on any error (fail-closed: a check failure blocks the grant)
     */
    public static boolean canFit(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull String itemId, int n) {
        if (n <= 0) {
            return true;
        }
        if (!ref.isValid()) {
            return false;
        }
        try {
            CombinedItemContainer inv = combined(store, ref);
            if (inv == null) {
                return false;
            }
            return inv.canAddItemStacks(java.util.List.of(new ItemStack(itemId, n)));
        } catch (Throwable t) {
            warn("canFit", itemId, t);
            return false;
        }
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
        return take(store, ref, itemId, n, InventoryComponent.EVERYTHING);
    }

    /** {@link #take} narrowed to {@code sections}, so nothing outside them is ever removed. */
    @SafeVarargs
    public static int take(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                           @Nonnull String itemId, int n,
                           @Nonnull ComponentType<EntityStore, ? extends InventoryComponent>... sections) {
        if (!ref.isValid() || n <= 0) {
            return 0;
        }
        try {
            CombinedItemContainer inv = InventoryComponent.getCombined(store, ref, sections);
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
        return spend(store, ref, itemId, n, InventoryComponent.EVERYTHING);
    }

    /**
     * {@link #spend} narrowed to {@code sections}: the check and the removal read the SAME view, so
     * a price can never be found affordable in one place and taken from another.
     */
    @SafeVarargs
    public static boolean spend(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                @Nonnull String itemId, int n,
                                @Nonnull ComponentType<EntityStore, ? extends InventoryComponent>... sections) {
        if (n <= 0) {
            return true;
        }
        if (!has(store, ref, itemId, n, sections)) {
            return false;
        }
        return take(store, ref, itemId, n, sections) >= n;
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
            CommonLog.LOGGER.atFine().log(
                    "[ZiggfreedCommon] InventoryUtil." + op + "(" + itemId + ") failed: " + t.getMessage());
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
