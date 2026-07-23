package com.ziggfreed.common.inventory;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

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
            warn("grant/hotbar", stack, t);
        }
        try {
            ItemContainer storage = storageOf(player);
            if (storage != null && storage.canAddItemStacks(List.of(stack))) {
                storage.addItemStack(stack);
                return Landed.STORAGE;
            }
        } catch (Throwable t) {
            warn("grant/storage", stack, t);
        }
        fallback.accept(stack);
        return Landed.FALLBACK;
    }

    @Nullable
    private static ItemContainer hotbarOf(@Nonnull Player player) {
        Ref<EntityStore> ref = refOf(player);
        if (ref == null) {
            return null;
        }
        InventoryComponent.Hotbar hotbar = ref.getStore()
                .getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        return hotbar != null ? hotbar.getInventory() : null;
    }

    @Nullable
    private static ItemContainer storageOf(@Nonnull Player player) {
        Ref<EntityStore> ref = refOf(player);
        if (ref == null) {
            return null;
        }
        InventoryComponent.Storage storage = ref.getStore()
                .getComponent(ref, InventoryComponent.Storage.getComponentType());
        return storage != null ? storage.getInventory() : null;
    }

    @Nullable
    private static Ref<EntityStore> refOf(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        return (ref != null && ref.isValid()) ? ref : null;
    }

    private static void warn(@Nonnull String op, @Nonnull ItemStack stack, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log(
                    "[ZiggfreedCommon] InventoryGrant." + op + "(" + stack.getItemId() + ") failed: " + t.getMessage());
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
