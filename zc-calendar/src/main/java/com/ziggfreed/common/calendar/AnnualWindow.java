package com.ziggfreed.common.calendar;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The days an event runs, year by year: an every-year {@link YearRule} (fixed month-days, a span around
 * Easter Sunday, or a span around the Nth weekday of a month) and days for particular years, which win
 * over the rule for their year.
 *
 * <p>Both days of a run are IN it: {@code 10-01} to {@code 11-03} runs from the first instant of October
 * 1st to the last instant of November 3rd. A run belongs to the year it STARTS in, and every run starts in
 * its own year (the asset refuses a rule whose runs could start in the year before), so the year of a
 * run's first instant, in the event's zone, is the run's year. February 29th falls back to the 28th in a
 * year without one ({@link MonthDay#atYear}).
 *
 * <p>A window made of per-year days alone has no run in a year it does not list, and none after its last.
 * {@link #nextStartMs} is bounded by that last year, or by {@link #LAST_YEAR}, so it can never loop.
 *
 * <p>Pure: no clock, no store, no engine type. Not {@code util/PeriodMath}: that answers fixed-length
 * windows on the UTC grid, and a calendar window is calendar arithmetic (month lengths, leap years, Easter,
 * a zone's own midnight), so it is java.time's.
 */
public final class AnnualWindow {

    /** The last year a run can be dated in: the last four-digit year. */
    public static final int LAST_YEAR = 9999;

    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{2})-(\\d{2})");

    @Nullable private final YearRule every;
    @Nonnull private final NavigableMap<Integer, YearRule.Fixed> years;

    private AnnualWindow(@Nullable YearRule every, @Nonnull NavigableMap<Integer, YearRule.Fixed> years) {
        this.every = every;
        this.years = years;
    }

    /** The every-year window from {@code first} to {@code last} ({@code MM-DD} each), or null when either is not a real day. */
    @Nullable
    public static AnnualWindow parse(@Nullable String first, @Nullable String last) {
        YearRule.Fixed fixed = fixed(first, last);
        return fixed == null ? null : new AnnualWindow(fixed, Collections.emptyNavigableMap());
    }

    /** Fixed month-days from two {@code MM-DD} strings, or null when either is not a real day. */
    @Nullable
    public static YearRule.Fixed fixed(@Nullable String first, @Nullable String last) {
        MonthDay from = monthDay(first);
        MonthDay to = monthDay(last);
        return from == null || to == null ? null : new YearRule.Fixed(from, to);
    }

    /**
     * The window an every-year rule and per-year days make, or null when there is neither. A per-year
     * entry wins over the rule for its year.
     *
     * @throws IllegalArgumentException for a rule that is not {@link YearRule#valid()}: its runs could start
     *         in the year before, and a run's year would no longer be its first day's
     */
    @Nullable
    public static AnnualWindow of(@Nullable YearRule every, @Nonnull Map<Integer, YearRule.Fixed> years) {
        if (every != null && !every.valid()) {
            throw new IllegalArgumentException("not a valid yearly rule: " + every.describe());
        }
        if (every == null && years.isEmpty()) {
            return null;
        }
        return new AnnualWindow(every, Collections.unmodifiableNavigableMap(new TreeMap<>(years)));
    }

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

    /** The days of the run that starts in {@code year}, or null when the window dates none that year. */
    @Nullable
    public RunDays days(int year) {
        if (year > LAST_YEAR) {
            return null;
        }
        YearRule.Fixed dated = years.get(year);
        if (dated != null) {
            return dated.days(year);
        }
        return every == null ? null : every.days(year);
    }

    /** Does the window date a run that starts in {@code year}? */
    public boolean hasRun(int year) {
        return days(year) != null;
    }

    /** Do the days differ from one year to the next: a moving rule (Easter, a weekday) or any per-year days? */
    public boolean moves() {
        return !years.isEmpty() || (every != null && every.moves());
    }

    /** Does the every-year rule cross the new year (fixed month-days whose last comes before its first)? */
    public boolean crossesNewYear() {
        return every instanceof YearRule.Fixed fixed && fixed.crossesNewYear();
    }

    /**
     * The first instant of the run that starts in {@code year}.
     *
     * @throws IllegalArgumentException when the window dates no run that year ({@link #hasRun})
     */
    public long startMs(int year, @Nonnull ZoneId zone) {
        return required(year).first().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * The first instant AFTER the run that starts in {@code year}: the midnight after its last day.
     *
     * @throws IllegalArgumentException when the window dates no run that year ({@link #hasRun})
     */
    public long endMs(int year, @Nonnull ZoneId zone) {
        return required(year).last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** The year whose run contains {@code nowMs}, or null when no run does. */
    @Nullable
    public Integer yearContaining(long nowMs, @Nonnull ZoneId zone) {
        int year = yearOf(nowMs, zone);
        if (contains(year, nowMs, zone)) {
            return year;
        }
        // A run that crosses the new year and started last year: no run lasts more than 366 days.
        if (contains(year - 1, nowMs, zone)) {
            return year - 1;
        }
        return null;
    }

    /**
     * The first year from {@code fromYear} on that has a run, or null when none is left: {@code fromYear}
     * itself under an every-year rule, else the first listed year from it. No loop.
     */
    @Nullable
    public Integer nextRunYear(int fromYear) {
        if (fromYear > LAST_YEAR) {
            return null;
        }
        return every != null ? Integer.valueOf(fromYear) : years.ceilingKey(fromYear);
    }

    /**
     * The first run start strictly after {@code nowMs}, never in a year before {@code fromYear}; null when no
     * run is left. Bounded: each step moves to a later year with a run, and runs start in their own year, so
     * it takes at most three steps under an every-year rule and at most one per listed year without one.
     */
    @Nullable
    public Long nextStartMs(long nowMs, @Nonnull ZoneId zone, int fromYear) {
        Integer year = nextRunYear(Math.max(fromYear, yearOf(nowMs, zone) - 1));
        while (year != null) {
            long start = startMs(year, zone);
            if (start > nowMs) {
                return start;
            }
            year = nextRunYear(year + 1);
        }
        return null;
    }

    private boolean contains(int year, long nowMs, @Nonnull ZoneId zone) {
        return hasRun(year) && nowMs >= startMs(year, zone) && nowMs < endMs(year, zone);
    }

    @Nonnull
    private RunDays required(int year) {
        RunDays run = days(year);
        if (run == null) {
            throw new IllegalArgumentException("the window dates no run in " + year);
        }
        return run;
    }

    /** {@code MM-DD..MM-DD} for fixed days, the rule's own data form otherwise, then any dated years. */
    @Override
    @Nonnull
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (every != null) {
            parts.add(every.describe());
        }
        if (!years.isEmpty()) {
            parts.add("Years " + String.join(", ", years.keySet().stream().map(String::valueOf).toList()));
        }
        return String.join(" + ", parts);
    }
}
