package com.ziggfreed.common.settings;

import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A player's own settings really changed: a spot picked or cleared, a surface shown, hidden or left to the
 * owner again, the switch over every bar panel, or the notification level. It replaces 2.1.0's
 * {@code ZigHudPreferenceChangedEvent}.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine bus, fired by {@link PlayerSettings} alone,
 * on the world thread, and NEVER for a write that changed nothing. {@link #setting()} names the choice in
 * the save's own words: {@code Hud.Spots.<surface>}, {@code Hud.Shown.<surface>}, {@code Hud.HideAll} or
 * {@code Notifications.Level}, the surface lower-cased. A listener that persists player data elsewhere (a
 * database-backed consumer) marks the player dirty off this event; the surfaces repaint through the
 * facade's own watchers and need no listener. {@link #playerRef()} is the live reference every library
 * write path resolved, and is null only in a test.
 */
public final class ZigPlayerSettingChangedEvent implements IEvent<Void> {

    public static final String HIDE_ALL = "Hud.HideAll";
    public static final String LEVEL = "Notifications.Level";
    public static final String SPOT_PREFIX = "Hud.Spots.";
    public static final String SHOWN_PREFIX = "Hud.Shown.";

    private final UUID playerId;
    @Nullable private final PlayerRef playerRef;
    private final String setting;

    public ZigPlayerSettingChangedEvent(@Nonnull UUID playerId, @Nullable PlayerRef playerRef,
            @Nonnull String setting) {
        this.playerId = playerId;
        this.playerRef = playerRef;
        this.setting = setting;
    }

    /** The setting name of a spot pick on {@code surface}. */
    @Nonnull
    public static String spot(@Nonnull String surface) {
        return SPOT_PREFIX + surface.trim().toLowerCase(Locale.ROOT);
    }

    /** The setting name of a Show choice on {@code surface}. */
    @Nonnull
    public static String shown(@Nonnull String surface) {
        return SHOWN_PREFIX + surface.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    @Nullable
    public PlayerRef playerRef() {
        return playerRef;
    }

    /** Which choice changed, in the save's own words. */
    @Nonnull
    public String setting() {
        return setting;
    }

    /** The lower-cased surface a spot or Show change is about, or null for the level or the hide-all switch. */
    @Nullable
    public String surface() {
        if (setting.startsWith(SPOT_PREFIX)) {
            return setting.substring(SPOT_PREFIX.length());
        }
        if (setting.startsWith(SHOWN_PREFIX)) {
            return setting.substring(SHOWN_PREFIX.length());
        }
        return null;
    }
}
