package com.ziggfreed.common.ui.hud;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to what a player has said about their own HUD ({@link HudPreferenceComponent}): the
 * reads a panel makes as it paints, and the writes the HUD settings page and the {@code /zighud}
 * verbs make on the player's behalf. Every write goes through here, so every REAL change is
 * announced once, as a native {@link ZigHudPreferenceChangedEvent} for whoever persists player data
 * elsewhere, and once to the {@linkplain #watch watchers} that redraw the player's panels.
 *
 * <p>Reads and writes need the player's entity, so they run on the player's world thread; a page
 * handler and a command body already do. A player with no component (the connect hook missed them,
 * or registration failed at boot) reads as having no preference and refuses a write with one line,
 * rather than attaching a component mid-tick from a screen.
 */
public final class HudPreferences {

    private static final List<Consumer<PlayerRef>> WATCHERS = new CopyOnWriteArrayList<>();

    private HudPreferences() {
    }

    /** Register the component and hang its connect hook. Call once from the library's setup, before any world loads. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            HudPreferenceComponent.register(plugin.getEntityStoreRegistry());
            HudPreferenceComponent.install(plugin);
        } catch (Throwable t) {
            SafeLog.warn("[hud] could not install the HUD preference component; picks will not persist this boot", t);
        }
    }

    /**
     * Be told when a player's preferences changed, to redraw what depends on them. The bar panels
     * register themselves here at install; a consumer drawing another shared overlay may too.
     */
    public static void watch(@Nonnull Consumer<PlayerRef> watcher) {
        WATCHERS.add(watcher);
    }

    /** Forget every watcher; for a test. */
    static void clearWatchersForTests() {
        WATCHERS.clear();
    }

    // ==================== reads ====================

    /** The player's component off their entity, or null for none. World thread. */
    @Nullable
    public static HudPreferenceComponent component(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return HudPreferenceComponent.TYPE == null ? null : store.getComponent(ref, HudPreferenceComponent.TYPE);
    }

    /** The player's component off their live reference, or null for none or a stale reference. World thread. */
    @Nullable
    public static HudPreferenceComponent component(@Nullable PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        return store == null ? null : component(store, ref);
    }

    /** The spot the player picked for {@code panelId}, lower-cased, or null for the server's own. */
    @Nullable
    public static String placementPick(@Nullable PlayerRef playerRef, @Nonnull String panelId) {
        HudPreferenceComponent prefs = component(playerRef);
        return prefs == null ? null : prefs.placementOf(panelId);
    }

    /** Whether the player hid {@code panelId}, on its own or through hide-all. */
    public static boolean isHidden(@Nullable PlayerRef playerRef, @Nonnull String panelId) {
        HudPreferenceComponent prefs = component(playerRef);
        return prefs != null && prefs.isHidden(panelId);
    }

    /** Whether the player hid every panel at once. */
    public static boolean isHideAll(@Nullable PlayerRef playerRef) {
        HudPreferenceComponent prefs = component(playerRef);
        return prefs != null && prefs.hideAll();
    }

    // ==================== writes ====================

    /**
     * Pick {@code placementId} for {@code panelId} on {@code playerRef}'s behalf, or clear the pick
     * with null so the server's own spot applies again. True when something changed and was
     * announced; false for no change, no component, or an id the save format refuses.
     */
    public static boolean setPlacementPick(@Nonnull PlayerRef playerRef, @Nonnull String panelId,
            @Nullable String placementId) {
        HudPreferenceComponent prefs = writable(playerRef);
        if (prefs == null || !prefs.setPlacement(panelId, placementId)) {
            return false;
        }
        changed(playerRef, panelId);
        return true;
    }

    /** Hide or show {@code panelId} for {@code playerRef}. True when something changed and was announced. */
    public static boolean setHidden(@Nonnull PlayerRef playerRef, @Nonnull String panelId, boolean hide) {
        HudPreferenceComponent prefs = writable(playerRef);
        if (prefs == null || !prefs.setHidden(panelId, hide)) {
            return false;
        }
        changed(playerRef, panelId);
        return true;
    }

    /** Hide or show every panel for {@code playerRef}. True when the switch moved and was announced. */
    public static boolean setHideAll(@Nonnull PlayerRef playerRef, boolean hide) {
        HudPreferenceComponent prefs = writable(playerRef);
        if (prefs == null || !prefs.setHideAll(hide)) {
            return false;
        }
        changed(playerRef, null);
        return true;
    }

    @Nullable
    private static HudPreferenceComponent writable(@Nonnull PlayerRef playerRef) {
        HudPreferenceComponent prefs = component(playerRef);
        if (prefs == null) {
            SafeLog.warn("[hud] " + playerRef.getUsername() + " has no HUD preference component, so the"
                    + " change was not kept; the component attaches at connect");
        }
        return prefs;
    }

    /** Announce a real change: the native event for whoever persists it, then every watcher redraws. */
    private static void changed(@Nonnull PlayerRef playerRef, @Nullable String panelId) {
        UUID uuid = playerRef.getUuid();
        String panel = panelId == null ? null : panelId.toLowerCase(Locale.ROOT);
        if (uuid != null) {
            HudPreferenceEvents.fireChanged(uuid, playerRef, panel);
        }
        for (Consumer<PlayerRef> watcher : WATCHERS) {
            try {
                watcher.accept(playerRef);
            } catch (Throwable t) {
                SafeLog.warn("[hud] a HUD preference watcher failed: " + t.getMessage());
            }
        }
    }
}
