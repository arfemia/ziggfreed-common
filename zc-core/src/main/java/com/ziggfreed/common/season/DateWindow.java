package com.ziggfreed.common.season;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A span of whole days that comes round every year, {@code MM-DD} to {@code MM-DD}, both days in, counted in UTC.
 *
 * <p>An end before its start crosses the new year ({@code 11-20} to {@code 01-06} holds Christmas and New Year's
 * Day), and the same day twice is that one day. February 29th falls back to the 28th in a year without one, as a
 * calendar event's days do ({@link MonthDay#atYear}).
 *
 * <p>Pure: no clock and no store. A caller passes the moment it asks about, so one moment serves the whole
 * question. {@link #monthDay} is the one {@code MM-DD} reader, shared with the calendar's windows.
 */
public record DateWindow(@Nonnull MonthDay start, @Nonnull MonthDay end) {

    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{2})-(\\d{2})");

    /** {@code MM-DD} as a month-day, or null for anything else ({@code 1-5}, {@code 02-30}, {@code 13-01}). */
    @Nullable
    public static MonthDay monthDay(@Nullable String text) {
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

    /** The window from {@code start} to {@code end} ({@code MM-DD} each), or null when either is not a real day. */
    @Nullable
    public static DateWindow parse(@Nullable String start, @Nullable String end) {
        MonthDay from = monthDay(start);
        MonthDay to = monthDay(end);
        return from == null || to == null ? null : new DateWindow(from, to);
    }

    /** Does the window cross the new year (its end falls before its start)? */
    public boolean wraps() {
        return end.isBefore(start);
    }

    /** Is the UTC day holding {@code nowMs} inside the window? */
    public boolean contains(long nowMs) {
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate first = start.atYear(today.getYear());
        LocalDate last = end.atYear(today.getYear());
        boolean fromStart = !today.isBefore(first);
        boolean toEnd = !today.isAfter(last);
        return wraps() ? fromStart || toEnd : fromStart && toEnd;
    }
}
