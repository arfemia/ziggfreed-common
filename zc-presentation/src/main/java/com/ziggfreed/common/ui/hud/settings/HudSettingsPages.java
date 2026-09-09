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
 * The way in to {@link HudSettingsPage}, and the one place a consumer says who may see its Server
 * tab and how it is painted.
 *
 * <p><b>The paradigm this page sets.</b> A setting about a shared surface is the library's to
 * present: the panels are the library's, the preference is the library's component, so the page
 * that edits them is the library's too, and a consumer's own settings menu LAUNCHES it (a button
 * calling {@link #open}) rather than growing a copy. One page, whichever mods are installed, and a
 * server running only this library still has it through {@code /zighud open}.
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

    /** Whether {@code player} may see the Server tab right now, by the registered audience. */
    static boolean mayAdminister(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        return resolvedDeps().mayAdministerGuarded(store, ref, player);
    }

    /** Open the page on its Mine tab for {@code player}. True when the screen was taken. */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        return open(store, ref, player, HudSettingsPage.TAB_MINE);
    }

    /**
     * Open the page on {@code tab} for {@code player}: {@link HudSettingsPage#TAB_MINE} for their
     * own picks, {@link HudSettingsPage#TAB_SERVER} for the owner defaults, which falls back to
     * Mine when the audience withholds it. True when the screen was taken; false when the entity
     * is not a player or the open threw.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull String tab) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[hud-settings] the page was asked for by an entity that is not a player");
            return false;
        }
        boolean admin = mayAdminister(store, ref, player);
        String shown = admin && HudSettingsPage.TAB_SERVER.equals(tab)
                ? HudSettingsPage.TAB_SERVER : HudSettingsPage.TAB_MINE;
        try {
            player.getPageManager().openCustomPage(ref, store, new HudSettingsPage(playerRef, shown, admin));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[hud-settings] the page failed to open", t);
            return false;
        }
    }
}
