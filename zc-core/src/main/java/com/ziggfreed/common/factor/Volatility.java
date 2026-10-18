package com.ziggfreed.common.factor;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How often a factor's reading can change, as its {@code Factors} file declares it ({@code Volatility}), which
 * is what decides whether a gate's verdict over it may be kept between two asks.
 *
 * <ul>
 *   <li>{@link #SERVER}: the same for everyone, and it changes only with the server itself (a mod installed);
 *       a verdict over it holds until the gate epoch moves.</li>
 *   <li>{@link #PLAYER}: one player's own state, which changes only through that player's moments, a
 *       permission change or a world change; a verdict over it holds per player until that player's epoch
 *       moves.</li>
 *   <li>{@link #MOMENT}: anything else (the hand, the target, the clock), the default for a factor whose
 *       file says nothing: never kept.</li>
 * </ul>
 *
 * <p>A closed vocabulary: the words are matched without regard to case, and an unknown word reads as
 * {@link #MOMENT}, the one answer that can never keep a stale verdict.
 */
public enum Volatility {

    SERVER("Server"),
    PLAYER("Player"),
    MOMENT("Moment");

    private final String word;

    Volatility(@Nonnull String word) {
        this.word = word;
    }

    /** The word a {@code Factors} file writes. */
    @Nonnull
    public String word() {
        return word;
    }

    /** The authored word read, {@link #MOMENT} for null, blank or anything it does not know. */
    @Nonnull
    public static Volatility of(@Nullable String authored) {
        Volatility known = known(authored);
        return known == null ? MOMENT : known;
    }

    /** The authored word read, or null when it is not one of the three (null and blank included). */
    @Nullable
    public static Volatility known(@Nullable String authored) {
        if (authored == null || authored.isBlank()) {
            return null;
        }
        String word = authored.trim().toLowerCase(Locale.ROOT);
        for (Volatility v : values()) {
            if (v.word.toLowerCase(Locale.ROOT).equals(word)) {
                return v;
            }
        }
        return null;
    }

    /** The more volatile of the two: what a gate reading both is. */
    @Nonnull
    public Volatility and(@Nonnull Volatility other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
