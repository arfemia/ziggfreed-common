package com.ziggfreed.common.feedback;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A thin wrapper over the engine {@code EventTitleUtil} centered banner API. Shows a
 * primary + secondary {@link Message} banner to one player (the caller builds the
 * localized messages; this util is config-free and reads no locale).
 *
 * <p>World-thread: writes a packet via the player's handler. Fully try-guarded.
 */
public final class EventTitles {

    /** Engine defaults (match {@code EventTitleUtil}: 4s shown, 1.5s fade). */
    public static final float DEFAULT_DURATION = 4.0F;
    public static final float DEFAULT_FADE_IN = 1.5F;
    public static final float DEFAULT_FADE_OUT = 1.5F;

    private EventTitles() {
    }

    /**
     * Show a centered event title banner with default timing.
     *
     * @param major when true, renders as the larger "major" banner style
     */
    public static void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary,
                            @Nonnull Message secondary, boolean major) {
        try {
            EventTitleUtil.showEventTitleToPlayer(playerRef, primary, secondary, major);
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("EventTitles.show failed: " + t.getMessage());
        }
    }

    /**
     * Show a centered event title banner with explicit timing and an optional icon.
     *
     * @param icon a status-icon asset id, or null for none
     */
    public static void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary,
                            @Nonnull Message secondary, boolean major, @Nullable String icon,
                            float duration, float fadeIn, float fadeOut) {
        try {
            EventTitleUtil.showEventTitleToPlayer(playerRef, primary, secondary, major,
                    icon, duration, fadeIn, fadeOut);
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("EventTitles.show failed: " + t.getMessage());
        }
    }

    /** Fade out / hide any current event title for this player. */
    public static void hide(@Nonnull PlayerRef playerRef, float fadeOutDuration) {
        try {
            EventTitleUtil.hideEventTitleFromPlayer(playerRef, fadeOutDuration);
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("EventTitles.hide failed: " + t.getMessage());
        }
    }
}
