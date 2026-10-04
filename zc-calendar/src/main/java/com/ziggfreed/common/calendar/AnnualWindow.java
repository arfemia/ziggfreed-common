package com.ziggfreed.common.calendar;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One yearly window: a first day and a last day, as month-days, that come round every year.
 *
 * <p>Both days are IN the window: {@code 10-01} to {@code 11-03} runs from the first instant of
 * October 1st to the last instant of November 3rd. A last day before the first crosses the new year
 * ({@code 12-20} to {@code 01-05}), and a run belongs to the year it STARTS in. February 29th falls
 * back to the 28th in a year without one ({@link MonthDay#atYear}).
 *
 * <p>Pure: no clock, no store, no engine type. Not {@code util/PeriodMath}: that answers fixed-length
 * windows on the UTC grid, and a month-day window is calendar arithmetic (month lengths, leap years,
 * a zone's own midnight), so it is java.time's.
 */
public final class AnnualWindow {

    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{2})-(\\d{2})");

    private final MonthDay first;
    private final MonthDay last;

    private AnnualWindow(@Nonnull MonthDay first, @Nonnull MonthDay last) {
        this.first = first;
        this.last = last;
    }

    /** The window from {@code first} to {@code last} ({@code MM-DD} each), or null when either is not a real day. */
    @Nullable
    public static AnnualWindow parse(@Nullable String first, @Nullable String last) {
        MonthDay from = monthDay(first);
        MonthDay to = monthDay(last);
        return from == null || to == null ? null : new AnnualWindow(from, to);
    }

    /** {@code MM-DD} as a month-day, or null for anything else ({@code 1-5}, {@code 02-30}, {@code 13-01}). */
    @Nullable
    static MonthDay monthDay(@Nullable String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MONTH_DAY.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            return MonthDay.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** The zone a {@code Clock} names: UTC when unauthored, null when java.time knows no such zone. */
    @Nullable
    public static ZoneId zone(@Nullable String clock) {
        if (clock == null || clock.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(clock.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** The calendar year {@code nowMs} falls in, in {@code zone}. */
    public static int yearOf(long nowMs, @Nonnull ZoneId zone) {
        return Instant.ofEpochMilli(nowMs).atZone(zone).getYear();
    }

    /** Does the window cross the new year (its last day comes before its first)? */
    public boolean crossesNewYear() {
        return last.isBefore(first);
    }

    /** The first instant of the run that starts in {@code year}. */
    public long startMs(int year, @Nonnull ZoneId zone) {
        return first.atYear(year).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** The first instant AFTER the run that starts in {@code year}: the midnight after its last day. */
    public long endMs(int year, @Nonnull ZoneId zone) {
        int lastYear = crossesNewYear() ? year + 1 : year;
        return last.atYear(lastYear).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** The year whose run contains {@code nowMs}, or null when no run does. */
    @Nullable
    public Integer yearContaining(long nowMs, @Nonnull ZoneId zone) {
        int year = yearOf(nowMs, zone);
        if (contains(year, nowMs, zone)) {
            return year;
        }
        // A run that crosses the new year and started last December.
        if (contains(year - 1, nowMs, zone)) {
            return year - 1;
        }
        return null;
    }

    /** The first run start strictly after {@code nowMs}, never in a year before {@code fromYear}. */
    public long nextStartMs(long nowMs, @Nonnull ZoneId zone, int fromYear) {
        int year = Math.max(fromYear, yearOf(nowMs, zone) - 1);
        while (startMs(year, zone) <= nowMs) {
            year++;
        }
        return startMs(year, zone);
    }

    private boolean contains(int year, long nowMs, @Nonnull ZoneId zone) {
        return nowMs >= startMs(year, zone) && nowMs < endMs(year, zone);
    }

    /** {@code MM-DD..MM-DD}, for a log line or an admin answer. */
    @Override
    @Nonnull
    public String toString() {
        return String.format(Locale.ROOT, "%02d-%02d..%02d-%02d", first.getMonthValue(), first.getDayOfMonth(),
                last.getMonthValue(), last.getDayOfMonth());
    }
}
