package com.ziggfreed.common.ui.hud;

import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.settings.PlayerSettings;
import com.ziggfreed.common.settings.PlayerSettingsComponent;

/**
 * The reads a shared bar panel makes as it paints, over the player's own settings
 * ({@link PlayerSettings}): where the player put a panel and whether it shows, with the owner's defaults
 * and locks already folded in. Writes go through {@link PlayerSettings} (the Settings tab and the
 * {@code /zighud} verbs do); this class keeps the panel-side names a mod drawing on a panel already reads.
 * World thread.
 */
public final class HudPreferences {

    private HudPreferences() {
    }

    /** Be told when a player's settings changed, to redraw a panel ({@link PlayerSettings#watch}). */
    public static void watch(@Nonnull Consumer<PlayerRef> watcher) {
        PlayerSettings.watch(watcher);
    }

    /** The player's settings record off their entity, or null for none. */
    @Nullable
    public static PlayerSettingsComponent component(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return PlayerSettings.component(store, ref);
    }

    /** The player's settings record off their live reference, or null for none or a stale reference. */
    @Nullable
    public static PlayerSettingsComponent component(@Nullable PlayerRef playerRef) {
        return PlayerSettings.component(playerRef);
    }

    /** The spot the player picked for {@code panelId}, lower-cased, or null for the server's own (or a lock). */
    @Nullable
    public static String placementPick(@Nullable PlayerRef playerRef, @Nonnull String panelId) {
        return PlayerSettings.spot(playerRef, panelId);
    }

    /** Whether {@code panelId} is hidden for the player: by the switch over every panel, or its own Show. */
    public static boolean isHidden(@Nullable PlayerRef playerRef, @Nonnull String panelId) {
        return PlayerSettings.hideAll(playerRef) || !PlayerSettings.shown(playerRef, panelId);
    }

    /** Whether the player hid every panel at once. */
    public static boolean isHideAll(@Nullable PlayerRef playerRef) {
        return PlayerSettings.hideAll(playerRef);
    }
}
