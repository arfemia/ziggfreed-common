package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How an objective's authored target is compared against the identifier an event carries.
 *
 * <p>The comparison itself is NOT here: it depends on the {@link MatchFlavor} the engine runs, and
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
        if (name == null) {
            return CONTAINS;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CONTAINS;
        }
    }
}
