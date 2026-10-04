package com.ziggfreed.common.feedback;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's one route to the engine's centered event-title banner: a primary and a secondary
 * {@link Message} shown to one player, in one of the engine's banner styles, with an optional sound. The
 * caller builds the localized messages; this class is config-free and reads no locale.
 *
 * <p><b>Two sizes, or any engine style.</b> The boolean forms ask for the ordinary banner or the larger
 * "major" one and draw it in the engine's {@code EventTitleStyle.Default} or {@code EventTitleStyle.Major},
 * the mapping the engine's own boolean overloads made before Update 7 marked them for removal; their short
 * form sends the engine's default timing for both sizes ({@link #DEFAULT_DURATION}, {@link #DEFAULT_FADE_IN},
 * {@link #DEFAULT_FADE_OUT}) and no icon, exactly what the boolean overloads sent. The style forms take the
 * engine's own {@code EventTitleStyle} ({@code Default}, {@code Major}, {@code GoblinBreach},
 * {@code VoidEviction}), so a style the game adds reaches a caller with no change here; their short forms
 * fade in the way the engine fades that style in ({@link #defaultFadeIn}).
 *
 * <p><b>Sound.</b> A style form may name a sound event, played to the same player as the banner shows (a
 * flat sound, not from a place), the way the engine plays an authored title's {@code SoundEventId}. An id
 * no pack ships plays nothing.
 *
 * <p><b>The second line is never null on the wire.</b> The engine's style overload reads the secondary
 * line although it marks it nullable, so a missing one goes out as an empty line and the headline still
 * shows.
 *
 * <p>World-thread: writes packets through the player's handler. Fully try-guarded: a failure, a missing
 * engine method included, logs at FINE and never reaches the caller.
 */
public final class EventTitles {

    /** How long a banner stays up, in seconds: the engine's own default. */
    public static final float DEFAULT_DURATION = 4.0F;
    /** How long a banner fades in, in seconds: the engine's own default, for both boolean sizes. */
    public static final float DEFAULT_FADE_IN = 1.5F;
    /** How long a banner fades out, in seconds: the engine's own default. */
    public static final float DEFAULT_FADE_OUT = 1.5F;

    /** Where a banner goes: the engine's per-player packets, or a test's recorder. */
    interface Sink {

        void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary, @Nonnull Message secondary,
                  @Nonnull EventTitleStyle style, @Nullable String icon, float duration, float fadeIn,
                  float fadeOut);

        void hide(@Nonnull PlayerRef playerRef, float fadeOut);

        /** A flat sound to the player; a recorder that does not care about sound keeps this no-op. */
        default void sound(@Nonnull PlayerRef playerRef, @Nonnull String soundEventId) {
        }
    }

    /** The engine's banner through its style overload, and its flat per-player sound. */
    private static final class EngineSink implements Sink {

        @Override
        public void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary, @Nonnull Message secondary,
                         @Nonnull EventTitleStyle style, @Nullable String icon, float duration, float fadeIn,
                         float fadeOut) {
            EventTitleUtil.showEventTitleToPlayer(playerRef, primary, secondary, style, icon, duration, fadeIn,
                    fadeOut);
        }

        @Override
        public void hide(@Nonnull PlayerRef playerRef, float fadeOut) {
            EventTitleUtil.hideEventTitleFromPlayer(playerRef, fadeOut);
        }

        @Override
        public void sound(@Nonnull PlayerRef playerRef, @Nonnull String soundEventId) {
            SoundUtil.playSoundEvent2dToPlayer(playerRef, soundEventId, SoundCategory.SFX);
        }
    }

    private static final Sink ENGINE = new EngineSink();

    private EventTitles() {
    }

    /**
     * Show a centered event title banner with default timing.
     *
     * @param major when true, renders as the larger "major" banner style
     */
    public static void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary,
                            @Nonnull Message secondary, boolean major) {
        showVia(ENGINE, playerRef, primary, secondary, major);
    }

    /**
     * Show a centered event title banner with explicit timing and an optional icon.
     *
     * @param icon a status-icon asset id, or null for none
     */
    public static void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary,
                            @Nonnull Message secondary, boolean major, @Nullable String icon,
                            float duration, float fadeIn, float fadeOut) {
        showVia(ENGINE, playerRef, primary, secondary, major, icon, duration, fadeIn, fadeOut);
    }

    /**
     * Show a banner in one of the engine's styles, with that style's default timing and no icon.
     *
     * @param soundEventId a sound event the player hears as the banner shows, or null for none
     */
    public static void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary,
                            @Nonnull Message secondary, @Nonnull EventTitleStyle style,
                            @Nullable String soundEventId) {
        showVia(ENGINE, playerRef, primary, secondary, style, soundEventId);
    }

    /**
     * Show a banner in one of the engine's styles with explicit timing, an optional icon and an optional sound.
     *
     * @param icon         a status-icon asset id, or null for none
     * @param soundEventId a sound event the player hears as the banner shows, or null for none
     */
    public static void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary,
                            @Nonnull Message secondary, @Nonnull EventTitleStyle style, @Nullable String icon,
                            float duration, float fadeIn, float fadeOut, @Nullable String soundEventId) {
        showVia(ENGINE, playerRef, primary, secondary, style, icon, duration, fadeIn, fadeOut, soundEventId);
    }

    /** Fade out / hide any current event title for this player. */
    public static void hide(@Nonnull PlayerRef playerRef, float fadeOutDuration) {
        hideVia(ENGINE, playerRef, fadeOutDuration);
    }

    /** How long the engine fades a banner of this style in, in seconds. */
    public static float defaultFadeIn(@Nonnull EventTitleStyle style) {
        return EventTitleUtil.getDefaultFadeInDuration(style);
    }

    /** The engine style a banner of this size is drawn in. */
    @Nonnull
    static EventTitleStyle style(boolean major) {
        return major ? EventTitleStyle.Major : EventTitleStyle.Default;
    }

    /** The boolean short form, onto any sink: the engine's default timing and no icon, for both sizes. */
    static void showVia(@Nonnull Sink sink, @Nonnull PlayerRef playerRef, @Nonnull Message primary,
                        @Nullable Message secondary, boolean major) {
        showVia(sink, playerRef, primary, secondary, major, null, DEFAULT_DURATION, DEFAULT_FADE_IN,
                DEFAULT_FADE_OUT);
    }

    /** The boolean full form, onto any sink: the size picks the style; no sound. */
    static void showVia(@Nonnull Sink sink, @Nonnull PlayerRef playerRef, @Nonnull Message primary,
                        @Nullable Message secondary, boolean major, @Nullable String icon, float duration,
                        float fadeIn, float fadeOut) {
        showVia(sink, playerRef, primary, secondary, style(major), icon, duration, fadeIn, fadeOut, null);
    }

    /** The style short form, onto any sink: the style's own fade-in, the default stay and fade-out, no icon. */
    static void showVia(@Nonnull Sink sink, @Nonnull PlayerRef playerRef, @Nonnull Message primary,
                        @Nullable Message secondary, @Nonnull EventTitleStyle style,
                        @Nullable String soundEventId) {
        float fadeIn;
        try {
            fadeIn = defaultFadeIn(style);
        } catch (Throwable t) {
            fadeIn = DEFAULT_FADE_IN;
        }
        showVia(sink, playerRef, primary, secondary, style, null, DEFAULT_DURATION, fadeIn, DEFAULT_FADE_OUT,
                soundEventId);
    }

    /** The full form, onto any sink: a missing second line goes out empty, and a named sound follows the banner. */
    static void showVia(@Nonnull Sink sink, @Nonnull PlayerRef playerRef, @Nonnull Message primary,
                        @Nullable Message secondary, @Nonnull EventTitleStyle style, @Nullable String icon,
                        float duration, float fadeIn, float fadeOut, @Nullable String soundEventId) {
        try {
            sink.show(playerRef, primary, secondary != null ? secondary : Msg.raw(""), style, icon, duration,
                    fadeIn, fadeOut);
            if (soundEventId != null && !soundEventId.isBlank()) {
                sink.sound(playerRef, soundEventId.trim());
            }
        } catch (Throwable t) {
            SafeLog.fine("EventTitles.show failed", t);
        }
    }

    static void hideVia(@Nonnull Sink sink, @Nonnull PlayerRef playerRef, float fadeOutDuration) {
        try {
            sink.hide(playerRef, fadeOutDuration);
        } catch (Throwable t) {
            SafeLog.fine("EventTitles.hide failed", t);
        }
    }
}
