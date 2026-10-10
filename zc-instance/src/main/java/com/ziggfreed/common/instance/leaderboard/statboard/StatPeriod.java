package com.ziggfreed.common.instance.leaderboard.statboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One stat period as the board runs it, resolved from its file: which window a stat moving now lands
 * in, which window its tab reads, and which buckets are its old windows.
 *
 * <p>A bucket is named by the period: {@code all_time} for the one that never ends,
 * {@code <id>@<window>} for a week or a month ({@code weekly@2026-W42}, {@code monthly@2026-10}), and
 * {@code range@<id>} for a range. A new window's key is the reset: nothing is cleared, the board simply
 * starts writing and reading a bucket nobody has written yet, and keeps the last {@link #keep} of them.
 *
 * @param id       the period's file id, lower-cased
 * @param nameKey  the tab's name, a translation key; null shows the id
 * @param order    where the tab sits, lowest first
 * @param zone     the clock a week, a month or a range is read on
 * @param startsOn the day a week starts on
 * @param keep     how many windows of a week or a month the board keeps, the running one included
 * @param from     a range's first moment, on {@code zone}'s clock; null for open
 * @param to       the moment a range ends (left out), on {@code zone}'s clock; null for open
 */
public record StatPeriod(@Nonnull String id, @Nonnull PeriodKind kind, @Nullable String nameKey, int order,
                         @Nonnull ZoneId zone, @Nonnull DayOfWeek startsOn, int keep,
                         @Nullable LocalDateTime from, @Nullable LocalDateTime to) {

    /** The bucket of the period that never ends. */
    public static final String ALL_TIME = "all_time";

    /** What joins a period to its window in a bucket key. */
    public static final String AT = "@";

    /** The bucket a stat moving at {@code now} lands in, or null while this period is not running (a range outside its dates). */
    @Nullable
    public String writeKey(@Nonnull Instant now) {
        return switch (kind) {
            case ALL_TIME -> ALL_TIME;
            case WEEKLY -> id + AT + PeriodWindows.weekKey(now, zone, startsOn);
            case MONTHLY -> id + AT + PeriodWindows.monthKey(now, zone);
            case RANGE -> PeriodWindows.within(now, zone, from, to) ? rangeKey() : null;
        };
    }

    /**
     * The bucket this period's tab reads at {@code now}: the running window, or a range from the moment
     * it starts, still readable after it ends; null for a range that has not started, which shows no tab.
     */
    @Nullable
    public String readKey(@Nonnull Instant now) {
        if (kind == PeriodKind.RANGE) {
            return PeriodWindows.started(now, zone, from) ? rangeKey() : null;
        }
        return writeKey(now);
    }

    /** What every window of a week or a month starts with, so the old ones can be dropped; null for a period of one window. */
    @Nullable
    public String windowPrefix() {
        return kind == PeriodKind.WEEKLY || kind == PeriodKind.MONTHLY ? id + AT : null;
    }

    @Nonnull
    private String rangeKey() {
        return "range" + AT + id;
    }
}
