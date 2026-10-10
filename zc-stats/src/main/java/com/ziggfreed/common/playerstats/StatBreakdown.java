package com.ziggfreed.common.playerstats;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Which part of a counted moment names the breakdown line a stat files it under, beside its grand
 * total. A closed vocabulary, authored as a stat file's {@code Breakdown}.
 */
public enum StatBreakdown {

    /** The grand total only. */
    NONE("None"),
    /** One line per target (the mob's role, the block, the item). */
    TARGET("Target"),
    /** One line per qualifier (the tier, the variant); a moment without one adds only to the total. */
    QUALIFIER("Qualifier"),
    /** One line per {@code target@qualifier}; a moment without a qualifier files under its target alone. */
    TARGET_AT_QUALIFIER("TargetAtQualifier");

    /** Joins a target to its qualifier in a {@link #TARGET_AT_QUALIFIER} line. */
    public static final String AT = "@";

    private final String authored;

    StatBreakdown(String authored) {
        this.authored = authored;
    }

    /** The word a file writes. */
    public String authored() {
        return authored;
    }

    /** The breakdown line a moment's {@code target} and {@code qualifier} name, or null for the total only. */
    @Nullable
    public String thing(@Nonnull String target, @Nullable String qualifier) {
        boolean hasTarget = !target.isBlank();
        boolean hasQualifier = qualifier != null && !qualifier.isBlank();
        return switch (this) {
            case NONE -> null;
            case TARGET -> hasTarget ? target.trim() : null;
            case QUALIFIER -> hasQualifier ? qualifier.trim() : null;
            case TARGET_AT_QUALIFIER -> !hasTarget ? null
                    : hasQualifier ? target.trim() + AT + qualifier.trim() : target.trim();
        };
    }

    /** The breakdown {@code value} names, matched without regard to case; None when unauthored, null when unknown. */
    @Nullable
    public static StatBreakdown parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String folded = value.trim().toLowerCase(Locale.ROOT);
        for (StatBreakdown breakdown : values()) {
            if (breakdown.authored.toLowerCase(Locale.ROOT).equals(folded)) {
                return breakdown;
            }
        }
        return null;
    }
}
