package com.ziggfreed.common.season;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.validation.Finding;

/**
 * The ONE reader of whether content's season is running right now.
 *
 * <p>A season is a calendar event's id ({@code Harvest_Feast}). For every event it loads, the calendar
 * declares the feature {@code <Id>_Live} in the {@value #NAMESPACE} namespace, reading 1 while a run is
 * going on (a force included) and 0 otherwise. This class reads that feature through
 * {@link FeatureFlags#read}, the very reading a lifted top-level {@code ziggfreedcommon:feature}
 * condition gets, so a {@code Season} leaf and a hand-written {@code <Id>_Live} hide gate can never
 * disagree. It never imports the calendar: the feature table is the seam.
 *
 * <p><b>Anything but a definite 1 hides.</b> A blank season is all year and always live. A season no
 * loaded event declares reads 0 (the namespace is declared, the feature is not), and a JVM with no
 * calendar at all reads null; both hide, and an audit names the first through {@link #checkKnown}.
 *
 * <p>A season is a HIDE, never a lock: a store reads it beside {@code Enabled} in its presence check
 * and never in a {@code Requires} block, so content out of season is absent rather than shown locked.
 */
public final class SeasonGate {

    /** The feature namespace the calendar declares its switches in, read as {@code ziggfreedcommon:feature}. */
    public static final String NAMESPACE = "ziggfreedcommon";

    /** Appended to an event id for the feature that reads 1 while that event runs. */
    public static final String LIVE_SUFFIX = "_Live";

    /** The finding code for a season no loaded calendar event declares. */
    public static final String UNKNOWN_SEASON = "UNKNOWN_SEASON";

    private SeasonGate() {
    }

    /** Does {@code season} name a season at all? Null and blank mean all year. */
    public static boolean isSeasonal(@Nullable String season) {
        return season != null && !season.isBlank();
    }

    /** {@code season} trimmed, or null when it names none: the spelling every store's getter answers. */
    @Nullable
    public static String normalize(@Nullable String season) {
        return isSeasonal(season) ? season.trim() : null;
    }

    /**
     * Is content of {@code season} present right now? True for a blank season; otherwise true only
     * while the feature {@code <season>_Live} reads 1, asked afresh on every call so a calendar
     * transition or a force lands on the next look with nothing rebuilt.
     */
    public static boolean live(@Nullable String season) {
        if (!isSeasonal(season)) {
            return true;
        }
        Double reading = FeatureFlags.read(NAMESPACE, featureOf(season));
        return reading != null && reading >= 1.0;
    }

    /** The feature id {@code season} reads: {@code <season>_Live}, the season trimmed. */
    @Nonnull
    public static String featureOf(@Nonnull String season) {
        return season.trim() + LIVE_SUFFIX;
    }

    /** Does a loaded calendar event declare {@code season}? A blank season is always known. */
    public static boolean known(@Nullable String season) {
        return !isSeasonal(season) || FeatureFlags.isKnown(NAMESPACE, featureOf(season));
    }

    /**
     * Add the {@link #UNKNOWN_SEASON} warning to {@code out} when {@code season} names an event no
     * loaded calendar file declares. Ask it from an audit or a fold that runs once every store has
     * loaded: asked before the calendar folded its events, every season reads unknown.
     */
    public static void checkKnown(@Nonnull List<Finding> out, @Nonnull String domain, @Nullable String season,
            @Nonnull String sourceId) {
        if (known(season)) {
            return;
        }
        out.add(Finding.warning(domain, UNKNOWN_SEASON, "Season names '" + season.trim()
                + "', which no calendar event on this server defines, so this stays hidden. Add that "
                + "event's calendar file, or correct the id.", sourceId));
    }
}
