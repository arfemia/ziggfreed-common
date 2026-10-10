package com.ziggfreed.common.instance.leaderboard.statboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The calendar arithmetic behind a stat period's windows, and nothing else: an instant in, a window
 * label out. Every label sorts as text in time order (a four-digit year, a two-digit week or month),
 * which is what lets a board drop its oldest windows by key alone ({@code Leaderboard.retain}).
 *
 * <p>A week is counted the way ISO counts it, starting on {@code startsOn} instead of always Monday: it
 * belongs to the year that holds its fourth day, so the last days of December can sit in week 1 of the
 * next year and the first days of January in week 52 or 53 of the last. With Monday it is exactly ISO.
 */
public final class PeriodWindows {

    private PeriodWindows() {
    }

    /** The week {@code now} falls in, in {@code zone}, as {@code 2026-W42}. */
    @Nonnull
    public static String weekKey(@Nonnull Instant now, @Nonnull ZoneId zone, @Nonnull DayOfWeek startsOn) {
        LocalDate date = now.atZone(zone).toLocalDate();
        WeekFields weeks = WeekFields.of(startsOn, 4);
        return String.format(Locale.ROOT, "%04d-W%02d",
                date.get(weeks.weekBasedYear()), date.get(weeks.weekOfWeekBasedYear()));
    }

    /** The month {@code now} falls in, in {@code zone}, as {@code 2026-10}. */
    @Nonnull
    public static String monthKey(@Nonnull Instant now, @Nonnull ZoneId zone) {
        YearMonth month = YearMonth.from(now.atZone(zone));
        return String.format(Locale.ROOT, "%04d-%02d", month.getYear(), month.getMonthValue());
    }

    /**
     * Whether {@code now}, read on the clock of {@code zone}, falls from {@code from} (included) up to
     * {@code to} (left out); a missing bound is open.
     */
    public static boolean within(@Nonnull Instant now, @Nonnull ZoneId zone, @Nullable LocalDateTime from,
                                 @Nullable LocalDateTime to) {
        LocalDateTime local = LocalDateTime.ofInstant(now, zone);
        return (from == null || !local.isBefore(from)) && (to == null || local.isBefore(to));
    }

    /** Whether {@code now}, read on the clock of {@code zone}, has reached {@code from}; a missing one always has. */
    public static boolean started(@Nonnull Instant now, @Nonnull ZoneId zone, @Nullable LocalDateTime from) {
        return within(now, zone, from, null);
    }
}
