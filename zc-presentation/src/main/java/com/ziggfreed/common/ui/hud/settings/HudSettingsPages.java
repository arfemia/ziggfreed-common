package com.ziggfreed.common.ui.hud.settings;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to {@link HudSettingsPage}, and the one place a consumer says who may see it and how it
 * is painted.
 *
 * <p><b>The paradigm this page sets.</b> A setting about a shared surface is the library's to
 * present: the panels are the library's, the preference is the library's component, so the page
 * that edits them is the library's too, and a consumer's own settings menu LAUNCHES it (a button
 * calling {@link #open}) rather than growing a copy. One page, whichever mods are installed, and a
 * player's own choices are on the Settings tab.
 *
 * <p>Deliberately NOT a registered destination: the Server tab writes the owner file, and an
 * admin surface must not be pack-addressable. The only routes here are this direct static call
 * from a consumer's own menu and the {@code /zighud} verb.
 *
 * <p>World thread.
 */
public final class HudSettingsPages {

    private static final AtomicReference<Supplier<HudSettingsDeps>> DEPS = new AtomicReference<>();

    private HudSettingsPages() {
    }

    /**
     * Say who may see the Server tab, how Back routes, and how the frame is painted
     * ({@link HudSettingsDeps}). Call once from a consumer's setup; pass null to go back to the
     * library defaults (which withhold the Server tab from everyone). Resolved lazily on each open.
     */
    public static void deps(@Nullable Supplier<HudSettingsDeps> supplier) {
        DEPS.set(supplier);
    }

    /** The deps in force right now: the registered consumer's, else the defaults. Guarded. */
    @Nonnull
    static HudSettingsDeps resolvedDeps() {
        Supplier<HudSettingsDeps> supplier = DEPS.get();
        if (supplier == null) {
            return HudSettingsDeps.DEFAULTS;
        }
        try {
            HudSettingsDeps deps = supplier.get();
            return deps != null ? deps : HudSettingsDeps.DEFAULTS;
        } catch (Throwable t) {
            SafeLog.warn("[hud-settings] the page deps failed to resolve: " + t.getMessage());
            return HudSettingsDeps.DEFAULTS;
        }
    }

    /** Whether {@code player} may see the server's HUD layout right now, by the registered audience. */
    public static boolean mayAdminister(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        return resolvedDeps().mayAdministerGuarded(store, ref, player);
    }

    /**
     * Open the server's HUD layout for {@code player}. False when the entity is not a player, when the
     * registered audience withholds it (a player's own HUD choices are on the Settings tab), or when the
     * open threw; the caller still owes the client an answer then.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[hud-settings] the page was asked for by an entity that is not a player");
            return false;
        }
        if (!mayAdminister(store, ref, player)) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new HudSettingsPage(playerRef));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[hud-settings] the page failed to open", t);
            return false;
        }
    }
}
