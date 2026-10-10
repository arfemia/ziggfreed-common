package com.ziggfreed.common.playerstats;

import java.util.Locale;

import javax.annotation.Nullable;

/** How a stat's number reads to a player. A closed vocabulary, authored as a stat file's {@code Format}. */
public enum StatFormat {

    /** A plain count. */
    COUNT("Count"),
    /** Seconds, read as a duration. */
    TIME("Time"),
    /** Metres, read as a distance. */
    DISTANCE("Distance"),
    /** A share out of 100. */
    PERCENT("Percent");

    private final String authored;

    StatFormat(String authored) {
        this.authored = authored;
    }

    /** The word a file writes. */
    public String authored() {
        return authored;
    }

    /** The format {@code value} names, matched without regard to case; Count when unauthored, null when unknown. */
    @Nullable
    public static StatFormat parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return COUNT;
        }
        String folded = value.trim().toLowerCase(Locale.ROOT);
        for (StatFormat format : values()) {
            if (format.authored.toLowerCase(Locale.ROOT).equals(folded)) {
                return format;
            }
        }
        return null;
    }
}
