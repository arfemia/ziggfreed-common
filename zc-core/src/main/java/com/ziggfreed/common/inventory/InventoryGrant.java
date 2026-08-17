package com.ziggfreed.common.inventory;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.ziggfreed.common.CommonLog;

/**
 * The generic HOTBAR-FIRST-IF-SPACE, then-backpack-storage GRANT-ORDERING primitive (round-5,
 * 2026-07-22, lifted here per the maintainer's common-lift amendment): puts a single
 * freshly-earned item stack somewhere the player can see/use it immediately (a non-held hotbar
 * slot, never necessarily the ACTIVE slot) before falling back to backpack storage, and finally
 * to a CALLER-SUPPLIED fallback (a world drop, a mail system, a log-and-lose - policy this class
 * deliberately does not own) when neither inventory section has room. Distinct from {@link
 * InventoryUtil} (which counts/gives/takes a resource item BY ID across the combined view for a
 * minigame's currency-shaped resource) - this is a single-stack, ORDER-SENSITIVE placement
 * decision for a genuine item grant.
 *
 * <p><b>This is a GRANT-side rule, not a consume-side one.</b> A consumer with its own
 * consume/drain path (reading a held tool, draining a crafting cost) should keep preferring
 * backpack storage over the hotbar there - mutating the HOTBAR container fans an Equipment
 * update to every viewer (including the acting player themselves), which has correlated with a
 * client-side rendering issue in at least one consumer's own smoke testing when it happens
 * mid-session under a locked/mounted server camera. Do NOT widen this hotbar-first order to a
 * consume/drain path; it is deliberately a grant-only convenience, and a grant firing while a
 * consumer's own session camera is locked is a new, previously-unexercised risk window worth
 * watching in that consumer's own in-game smoke pass.
 *
 * <p>World-thread only (touches the {@link com.hypixel.hytale.component.Store}); every step is
 * try-guarded so a missing component / invalid ref / engine throw degrades to the next fallback,
 * never a throw into the caller.
 */
public final class InventoryGrant {

    private InventoryGrant() {
    }

    /** Where {@link #grant} landed the stack. */
    public enum Landed { HOTBAR, STORAGE, FALLBACK }

    /**
     * Would {@link #grant} put {@code stack} in an inventory section right now, WITHOUT putting
     * anything anywhere? The question to ask before charging a price, spending a completion, or
     * telling a player their reward is ready: a grant that reaches the fallback is a reward the
     * player did not receive, and a probe first is how a caller avoids paying for that.
     *
     * <p>Answers false when it cannot tell (no inventory, an invalid reference, an engine throw),
     * because the useful failure here is "we did not hand it over" rather than "we handed it
     * somewhere and lost track".
     */
    public static boolean canAdd(@Nonnull Player player, @Nonnull ItemStack stack) {
        return canAddAll(player, List.of(stack));
    }

    /**
     * Would ALL of {@code stacks} land, given together? Empty answers true.
     *
     * <p>A batch is checked against backpack STORAGE alone, while a lone stack also gets the
     * hotbar. That is not an oversight: the hotbar is a per-stack placement decision {@link #grant}
     * makes one stack at a time, so no single container answers "do these five fit between the
     * two of them". Storage is where every stack ends up when the hotbar is full, so a batch that
     * fits there fits full stop - the answer can only ever UNDER-promise, which is the safe
     * direction for a caller about to charge somebody.
     *
     * <p><b>Pair a probe with its own granter.</b> This one mirrors {@link #grant}; {@code
     * InventoryUtil.canFit} mirrors {@code InventoryUtil.give} across the combined view. Reading
     * one and then calling the other is how a "checked" grant still lands somewhere nobody
     * expected.
     */
    public static boolean canAddAll(@Nonnull Player player, @Nonnull List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return true;
        }
        try {
            ItemContainer storage = storageOf(player);
            if (storage != null && storage.canAddItemStacks(stacks)) {
                return true;
            }
        } catch (Throwable t) {
            warn("canAdd/storage", label(stacks), t);
        }
        if (stacks.size() != 1) {
            return false;
        }
        try {
            ItemContainer hotbar = hotbarOf(player);
            return hotbar != null && hotbar.canAddItemStacks(stacks);
        } catch (Throwable t) {
            warn("canAdd/hotbar", label(stacks), t);
            return false;
        }
    }

    /**
     * Hotbar-first (only when the WHOLE stack fits), then backpack storage, then {@code
     * fallback} (invoked with {@code stack} unchanged - the caller decides what "no room
     * anywhere" means). Never throws.
     */
    @Nonnull
    public static Landed grant(@Nonnull Player player, @Nonnull ItemStack stack,
            @Nonnull Consumer<ItemStack> fallback) {
        try {
            ItemContainer hotbar = hotbarOf(player);
            if (hotbar != null && hotbar.canAddItemStacks(List.of(stack))) {
                hotbar.addItemStack(stack);
                return Landed.HOTBAR;
            }
        } catch (Throwable t) {
            warn("grant/hotbar", stack.getItemId(), t);
        }
        try {
            ItemContainer storage = storageOf(player);
            if (storage != null && storage.canAddItemStacks(List.of(stack))) {
                storage.addItemStack(stack);
                return Landed.STORAGE;
            }
        } catch (Throwable t) {
            warn("grant/storage", stack.getItemId(), t);
        }
        fallback.accept(stack);
        return Landed.FALLBACK;
    }

    // The section reads themselves go through PlayerAccess (same package), so the ref guard and
    // the component fetch have one home; only the unwrap to the section's own container is here.
    @Nullable
    private static ItemContainer hotbarOf(@Nonnull Player player) {
        InventoryComponent.Hotbar hotbar = PlayerAccess.hotbar(player);
        return hotbar != null ? hotbar.getInventory() : null;
    }

    @Nullable
    private static ItemContainer storageOf(@Nonnull Player player) {
        InventoryComponent.Storage storage = PlayerAccess.storage(player);
        return storage != null ? storage.getInventory() : null;
    }

    @Nonnull
    private static String label(@Nonnull List<ItemStack> stacks) {
        return stacks.size() == 1 ? stacks.get(0).getItemId() : stacks.size() + " stacks";
    }

    private static void warn(@Nonnull String op, @Nonnull String what, @Nonnull Throwable t) {
        try {
            CommonLog.LOGGER.atFine().log(
                    "[ZiggfreedCommon] InventoryGrant." + op + "(" + what + ") failed: " + t.getMessage());
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
