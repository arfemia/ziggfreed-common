package com.ziggfreed.common.instance.leaderboard.statboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a stat period's {@code Kind} names: how its windows are cut. A closed vocabulary, since a
 * window rule is code; each kind is authored under its own word.
 */
public enum PeriodKind {

    /** One window that never ends: the bucket {@code all_time}. */
    ALL_TIME("AllTime"),
    /** A calendar week from {@code StartsOn}, in {@code Zone}: {@code <id>@2026-W42}. */
    WEEKLY("Weekly"),
    /** A calendar month in {@code Zone}: {@code <id>@2026-10}. */
    MONTHLY("Monthly"),
    /** One window between {@code From} and {@code To}: {@code range@<id>}, readable after it ends. */
    RANGE("Range");

    private final String authored;

    PeriodKind(@Nonnull String authored) {
        this.authored = authored;
    }

    /** The word a file names this kind by. */
    @Nonnull
    public String authored() {
        return authored;
    }

    /** The kind a file names, matched without regard to case; null for a word this library does not know. */
    @Nullable
    public static PeriodKind fromAuthored(@Nullable String word) {
        if (word == null) {
            return null;
        }
        String trimmed = word.trim();
        for (PeriodKind kind : values()) {
            if (kind.authored.equalsIgnoreCase(trimmed)) {
                return kind;
            }
        }
        return null;
    }
}
