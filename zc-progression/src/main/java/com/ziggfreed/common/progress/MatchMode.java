package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How an objective's authored target is compared against the identifier an event carries.
 *
 * <p>The comparison itself is NOT here: it lives in {@link ObjectiveMatch}, and
 * both flavors live in {@link ObjectiveMatch}. This enum only names the three shapes an author can
 * ask for.
 *
 * <p>{@link #CONTAINS} is the parse default, so an author who omits the field gets the forgiving
 * comparison rather than a silent never-matches.
 */
public enum MatchMode {

    /** The whole identifier must equal the target. */
    EXACT,

    /** The identifier must contain the target anywhere inside it. */
    CONTAINS,

    /** The identifier must start with the target. */
    PREFIX;

    /** Parse a case-insensitive name, falling back to {@link #CONTAINS} for null/unknown input. */
    @Nonnull
    public static MatchMode fromString(@Nullable String name) {
        return fromString(name, CONTAINS);
    }

    /**
     * Parse a case-insensitive name, falling back to {@code fallback} for null/unknown input. The
     * target leaf falls back to {@link #CONTAINS}; the qualifier leaf falls back to {@link #EXACT},
     * because every objective authored before the qualifier had a comparison of its own compared it
     * whole, and that meaning must not move.
     */
    @Nonnull
    public static MatchMode fromString(@Nullable String name, @Nonnull MatchMode fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
