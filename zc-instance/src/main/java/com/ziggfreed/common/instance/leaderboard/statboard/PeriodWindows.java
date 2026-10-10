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
 * label out. Every label sorts as text in time order (a four-digit year, a two-digit week, month or run
 * number), which is what lets a board drop its oldest windows by key alone ({@code Leaderboard.retain}).
 *
 * <p>A week is counted the way ISO counts it, starting on {@code startsOn} instead of always Monday: it
 * belongs to the year that holds its fourth day, so the last days of December can sit in week 1 of the
 * next year and the first days of January in week 52 or 53 of the last. With Monday it is exactly ISO.
 */
public final class PeriodWindows {

    /** Joins a season window's event to its run. */
    public static final char RUN_MARK = ':';

    /** Joins a run's year to its number, for every run of a year but the first. */
    public static final char NUMBER_MARK = '#';

    /** One season window read back: the event, the year its run belongs to, and the run's number within it. */
    public record Run(@Nonnull String eventId, int year, int number) {
    }

    private PeriodWindows() {
    }

    /**
     * A season's window for run {@code number} of {@code year} of {@code eventId}: {@code hallows_eve:2026} for
     * a year's run 1, {@code hallows_eve:2026#09} for its run 9. The year is four digits and a later run's
     * number two (a number is at most 54), so one event's windows sort as text by year and then by number,
     * which is the order its runs come round whenever its numbers follow its dates: a once-a-year event, a
     * monthly or weekly one, a list of spans written in date order.
     */
    @Nonnull
    public static String runWindow(@Nonnull String eventId, int year, int number) {
        String run = number <= 1 ? String.format(Locale.ROOT, "%04d", year)
                : String.format(Locale.ROOT, "%04d%c%02d", year, NUMBER_MARK, number);
        return runWindowPrefix(eventId) + run;
    }

    /** What every season window of {@code eventId} starts with: {@code hallows_eve:}. */
    @Nonnull
    public static String runWindowPrefix(@Nonnull String eventId) {
        return eventId.trim().toLowerCase(Locale.ROOT) + RUN_MARK;
    }

    /** The run {@link #runWindow} wrote {@code window} for, or null for text it never writes. */
    @Nullable
    public static Run parseRunWindow(@Nullable String window) {
        if (window == null) {
            return null;
        }
        int mark = window.lastIndexOf(RUN_MARK);
        if (mark <= 0 || mark == window.length() - 1) {
            return null;
        }
        String run = window.substring(mark + 1);
        int hash = run.indexOf(NUMBER_MARK);
        try {
            int year = Integer.parseInt(hash < 0 ? run : run.substring(0, hash));
            int number = hash < 0 ? 1 : Integer.parseInt(run.substring(hash + 1));
            return year > 0 && number >= 1 ? new Run(window.substring(0, mark), year, number) : null;
        } catch (NumberFormatException notARun) {
            return null;
        }
    }

    /**
     * Can {@code eventId} name a season window? Not blank, and free of the marks a bucket key or a tab
     * reserves: {@code @} (the period's join), {@code :} (the run's), {@code |}, {@code *} and {@code /}.
     */
    public static boolean usableEvent(@Nullable String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        for (char reserved : new char[] {'@', RUN_MARK, '|', '*', '/'}) {
            if (eventId.indexOf(reserved) >= 0) {
                return false;
            }
        }
        return true;
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
