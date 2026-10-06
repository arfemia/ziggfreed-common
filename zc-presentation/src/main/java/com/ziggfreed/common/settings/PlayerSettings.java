package com.ziggfreed.common.settings;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to what a player chose for their own screen ({@link PlayerSettingsComponent}): the reads a
 * surface makes as it draws and the writes the Settings tab and the {@code /zighud} verbs make on the
 * player's behalf.
 *
 * <p><b>Every read is the effective answer.</b> A surface's owner rules ({@link #rules}: a HUD panel's
 * {@code Player} group, else the player-settings record's {@code QuestTracker}) and the level's
 * ({@link #levelRules}) fold over the stored choice: a lock answers the owner's value, else the player's
 * own choice, else the owner's default. A lock never touches the stored choice, so lifting it brings the
 * player's choice back.
 *
 * <p><b>Every REAL write is announced once</b>, as a native {@link ZigPlayerSettingChangedEvent} for
 * whoever persists player data elsewhere, then to the {@linkplain #watch watchers} that redraw the
 * player's surfaces; a write that changes nothing announces nothing.
 *
 * <p>Reads and writes need the player's entity, so they run on the player's world thread (a page handler
 * and a command body already do). A player with no record (the connect hook missed them, or registration
 * failed at boot) reads as having chosen nothing and refuses a write with one line, rather than attaching
 * a component mid-tick from a screen.
 */
public final class PlayerSettings {

    /** The quest tracker's surface id: what a HUD spot's {@code Panels} names to be offered to it. */
    public static final String QUEST_TRACKER = "Quest_Tracker";

    private static final List<Consumer<PlayerRef>> WATCHERS = new CopyOnWriteArrayList<>();
    private static final NativeEventSeam EVENTS = new NativeEventSeam("[settings]");

    private PlayerSettings() {
    }

    /** Register the record and hang its connect hook. Once, from the library's setup, before any world loads. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            PlayerSettingsComponent.register(plugin.getEntityStoreRegistry());
            PlayerSettingsComponent.install(plugin);
        } catch (Throwable t) {
            SafeLog.warn("[settings] could not install the player settings record; choices will not persist this boot", t);
        }
    }

    /** Be told when a player's settings changed, to redraw what depends on them. World thread. */
    public static void watch(@Nonnull Consumer<PlayerRef> watcher) {
        WATCHERS.add(watcher);
    }

    /** Forget every watcher; for a test. */
    static void clearWatchersForTests() {
        WATCHERS.clear();
    }

    /** Route the event through {@code publisher} instead of the engine bus; null restores the bus. */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        EVENTS.publishTo(publisher);
    }

    // ==================== reads ====================

    /** The player's record off their entity, or null for none. World thread. */
    @Nullable
    public static PlayerSettingsComponent component(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return PlayerSettingsComponent.TYPE == null ? null : store.getComponent(ref, PlayerSettingsComponent.TYPE);
    }

    /** The player's record off their live reference, or null for none or a stale reference. World thread. */
    @Nullable
    public static PlayerSettingsComponent component(@Nullable PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        return store == null ? null : component(store, ref);
    }

    /** Whether a write for this player would be kept: they have a record. World thread. */
    public static boolean writable(@Nullable PlayerRef playerRef) {
        return component(playerRef) != null;
    }

    /** The owner's rules over {@code surface}: the tracker's from the record, a panel's from its file. */
    @Nonnull
    public static SurfaceRules rules(@Nonnull String surface) {
        String id = surface.trim();
        if (QUEST_TRACKER.equalsIgnoreCase(id)) {
            return PlayerSettingsConfig.getInstance().questTracker();
        }
        return HudPanelConfig.getInstance().panel(id).player();
    }

    /** The owner's rules over the notification level. */
    @Nonnull
    public static NotificationRules.LevelRule levelRules() {
        return PlayerSettingsConfig.getInstance().level();
    }

    /** Whether {@code surface} shows for this player (the hide-all switch is the panels' own business). */
    public static boolean shown(@Nullable PlayerRef playerRef, @Nonnull String surface) {
        return effectiveShown(component(playerRef), surface, rules(surface));
    }

    /** {@link #shown(PlayerRef, String)} for a caller holding the entity handles. World thread. */
    public static boolean shown(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String surface) {
        return effectiveShown(component(store, ref), surface, rules(surface));
    }

    /** The spot this player picked for {@code surface}, lower-cased, or null for the server's own. */
    @Nullable
    public static String spot(@Nullable PlayerRef playerRef, @Nonnull String surface) {
        return effectiveSpot(component(playerRef), surface, rules(surface));
    }

    /** Whether this player hid every bar panel at once. */
    public static boolean hideAll(@Nullable PlayerRef playerRef) {
        PlayerSettingsComponent settings = component(playerRef);
        return settings != null && settings.hideAll();
    }

    /** This player's notification level. */
    @Nonnull
    public static NotificationLevel level(@Nullable PlayerRef playerRef) {
        return effectiveLevel(component(playerRef), levelRules());
    }

    /**
     * The level a toast is graded by, or null when it cannot be read here (no player, a stale reference, or
     * a caller off the player's world thread), in which case the toast keeps what its moment authored.
     */
    @Nullable
    public static NotificationLevel levelForToast(@Nullable PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null || !store.isInThread()) {
            return null;
        }
        return effectiveLevel(component(store, ref), levelRules());
    }

    // ==================== the fold ====================

    /** The effective Show: a lock's value, else the player's choice, else the owner's default. */
    public static boolean effectiveShown(@Nullable PlayerSettingsComponent settings, @Nonnull String surface,
            @Nonnull SurfaceRules rules) {
        if (rules.showLocked()) {
            return rules.showDefault();
        }
        Boolean chosen = settings == null ? null : settings.shown(surface);
        return chosen != null ? chosen : rules.showDefault();
    }

    /** The effective spot pick: none under a lock, else the player's pick. */
    @Nullable
    public static String effectiveSpot(@Nullable PlayerSettingsComponent settings, @Nonnull String surface,
            @Nonnull SurfaceRules rules) {
        return rules.spotLocked() || settings == null ? null : settings.spot(surface);
    }

    /** The effective level: a lock's default, else the player's choice, else the owner's default. */
    @Nonnull
    public static NotificationLevel effectiveLevel(@Nullable PlayerSettingsComponent settings,
            @Nonnull NotificationRules.LevelRule rules) {
        if (rules.locked()) {
            return rules.defaultLevel();
        }
        NotificationLevel chosen = settings == null ? null : settings.level();
        return chosen != null ? chosen : rules.defaultLevel();
    }

    // ==================== writes ====================

    /** Show or hide {@code surface} for this player, or hand it back to the owner's default with null. */
    public static boolean setShown(@Nonnull PlayerRef playerRef, @Nonnull String surface, @Nullable Boolean shown) {
        return commit(recordFor(playerRef, surface), playerRef.getUuid(), playerRef,
                ZigPlayerSettingChangedEvent.shown(surface), settings -> settings.setShown(surface, shown));
    }

    /** Pick {@code spotId} for {@code surface}, or clear the pick with null so the server's spot applies. */
    public static boolean setSpot(@Nonnull PlayerRef playerRef, @Nonnull String surface, @Nullable String spotId) {
        return commit(recordFor(playerRef, surface), playerRef.getUuid(), playerRef,
                ZigPlayerSettingChangedEvent.spot(surface), settings -> settings.setSpot(surface, spotId));
    }

    /** Hide or show every bar panel for this player. */
    public static boolean setHideAll(@Nonnull PlayerRef playerRef, boolean hide) {
        return commit(recordFor(playerRef, "hide-all"), playerRef.getUuid(), playerRef,
                ZigPlayerSettingChangedEvent.HIDE_ALL, settings -> settings.setHideAll(hide));
    }

    /** Choose this player's notification level, or hand it back to the owner's default with null. */
    public static boolean setLevel(@Nonnull PlayerRef playerRef, @Nullable NotificationLevel level) {
        return commit(recordFor(playerRef, "level"), playerRef.getUuid(), playerRef,
                ZigPlayerSettingChangedEvent.LEVEL, settings -> settings.setLevel(level));
    }

    @Nullable
    private static PlayerSettingsComponent recordFor(@Nonnull PlayerRef playerRef, @Nonnull String what) {
        PlayerSettingsComponent settings = component(playerRef);
        if (settings == null) {
            SafeLog.warn("[settings] " + playerRef.getUsername() + " has no settings record, so the " + what
                    + " change was not kept; the record attaches at connect");
        }
        return settings;
    }

    /**
     * Apply {@code write} and announce it when it changed something: the event (for a known player) and
     * then every watcher (for a live reference). False for no record or no change. Package-private so a
     * test drives it with a bare record.
     */
    static boolean commit(@Nullable PlayerSettingsComponent settings, @Nullable UUID playerId,
            @Nullable PlayerRef playerRef, @Nonnull String setting,
            @Nonnull Predicate<PlayerSettingsComponent> write) {
        if (settings == null || !write.test(settings)) {
            return false;
        }
        if (playerId != null) {
            EVENTS.fire("ZigPlayerSettingChanged", ZigPlayerSettingChangedEvent.class,
                    () -> new ZigPlayerSettingChangedEvent(playerId, playerRef, setting));
        }
        if (playerRef != null) {
            for (Consumer<PlayerRef> watcher : WATCHERS) {
                try {
                    watcher.accept(playerRef);
                } catch (Throwable t) {
                    SafeLog.warn("[settings] a settings watcher failed: " + t.getMessage());
                }
            }
        }
        return true;
    }
}
