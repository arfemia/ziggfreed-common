package com.ziggfreed.common.playerstats;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * What a stat's number MEANS, which decides how a moment moves it and where it lives. A closed
 * vocabulary, authored as a stat file's {@code Kind}.
 */
public enum StatKind {

    /** A running total: each counted moment adds to it. Kept in the player's record. */
    SUM("Sum"),
    /** A best that grows: a counted moment raises it when it beats it. Kept in the player's record. */
    MAX("Max"),
    /** A best that shrinks, such as a fastest time: a counted moment lowers it. Kept in the player's record. */
    MIN("Min"),
    /** A number a mod owns and sets (a level): ranked, never kept in the player's record. */
    GAUGE("Gauge"),
    /** A number the engine already records: read, never counted again, never kept here. */
    NATIVE("Native");

    private final String authored;

    StatKind(String authored) {
        this.authored = authored;
    }

    /** The word a file writes. */
    public String authored() {
        return authored;
    }

    /** Is this kind counted into the player's record (Sum, Max, Min)? */
    public boolean counted() {
        return this == SUM || this == MAX || this == MIN;
    }

    /** The kind {@code value} names, matched without regard to case; Sum when unauthored, null when unknown. */
    @Nullable
    public static StatKind parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return SUM;
        }
        String folded = value.trim().toLowerCase(Locale.ROOT);
        for (StatKind kind : values()) {
            if (kind.authored.toLowerCase(Locale.ROOT).equals(folded)) {
                return kind;
            }
        }
        return null;
    }
}
