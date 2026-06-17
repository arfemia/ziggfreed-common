package com.ziggfreed.common.ui;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Minimal helpers for installing a custom {@link CustomUIHud} overlay and managing
 * the native HUD strip/restore around it. The consumer authors and constructs its
 * own concrete {@code CustomUIHud} subclass (the helper cannot, since the overlay's
 * {@code .ui} markup + element-id contract are mod-specific) and passes the instance
 * in; this helper owns only the register / strip / restore plumbing every overlay
 * repeats.
 *
 * <p><b>Limitation:</b> because the HUD subclass is consumer-owned, this is a thin
 * register/strip facade, not a generic HUD. The consumer keeps a reference to the
 * passed-in HUD instance for later partial {@code update(false, cb)} pushes - this
 * helper does not retain it.
 *
 * <p>World-thread only (reads the {@link Player} / {@link PlayerRef} components and
 * writes HUD packets). Fully try-guarded so a HUD failure never throws into the
 * round loop.
 */
public final class CustomHudHelper {

    private CustomHudHelper() {
    }

    /**
     * Install a custom HUD overlay for a player and strip the native HUD down to the
     * given kept components (pass {@link #defaultKeptHud()} for a sensible minigame
     * default, or your own set).
     *
     * @param hud  the consumer-constructed overlay instance (the consumer should
     *             retain it for later {@code update(false, cb)} pushes)
     * @param kept the native {@link HudComponent}s to keep visible alongside the overlay
     * @return true if the overlay was installed, false on failure (missing components)
     */
    public static boolean install(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                  @Nonnull CustomUIHud hud, @Nonnull Set<HudComponent> kept) {
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                return false;
            }
            HudManager manager = player.getHudManager();
            manager.addCustomHud(playerRef, hud);
            manager.setVisibleHudComponents(playerRef, kept);
            return true;
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("CustomHudHelper.install failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Remove ONE custom HUD by key without otherwise touching the native HUD set.
     * (Use {@link #restore} to also reset the stripped native HUD.)
     */
    public static void remove(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                              @Nonnull String hudKey) {
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                return;
            }
            player.getHudManager().removeCustomHud(playerRef, hudKey);
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("CustomHudHelper.remove failed: " + t.getMessage());
        }
    }

    /**
     * Restore the default native HUD and drop all custom overlays for a player. The
     * symmetric teardown for {@link #install}; call on exit / death / round-end.
     */
    public static void restore(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                return;
            }
            player.getHudManager().resetHud(playerRef);
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("CustomHudHelper.restore failed: " + t.getMessage());
        }
    }

    /**
     * Restrict the visible native HUD to the given components (without installing an
     * overlay). The strip half of {@link #install}, exposed for callers that only
     * want to hide native HUD chrome.
     */
    public static void strip(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                             @Nonnull Set<HudComponent> kept) {
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || playerRef == null) {
                return;
            }
            player.getHudManager().setVisibleHudComponents(playerRef, kept);
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("CustomHudHelper.strip failed: " + t.getMessage());
        }
    }

    /**
     * A sensible default kept-HUD set for a minigame overlay: the gameplay-essential
     * native elements plus the notification / event-title channels (so toasts and
     * banners still render over the overlay). Mirrors the Kweebec round HUD set.
     */
    @Nonnull
    public static Set<HudComponent> defaultKeptHud() {
        return Set.of(
                HudComponent.Hotbar,
                HudComponent.Health,
                HudComponent.Mana,
                HudComponent.Stamina,
                HudComponent.Reticle,
                HudComponent.StatusIcons,
                HudComponent.Notifications,
                HudComponent.EventTitle);
    }
}
