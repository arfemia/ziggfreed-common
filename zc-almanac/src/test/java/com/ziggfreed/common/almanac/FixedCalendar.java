package com.ziggfreed.common.almanac;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;

/**
 * A calendar a test writes by hand: per season, the runs the real calendar would answer. A season is
 * live while its {@link AlmanacCalendar.Dates#live()} names a run, between seasons otherwise, and absent
 * when it was never added. Also the one way the module's tests spell a moment and a run.
 */
public final class FixedCalendar implements AlmanacCalendar {

    /** The clock a season counts its days in unless a test names another. */
    public static final ZoneId UTC = ZoneOffset.UTC;

    /** The season id the module's fixture page uses. */
    public static final String TEST_SEASON = "test_season";

    private final Map<String, Dates> seasons = new HashMap<>();

    /** Add (or replace) a season the calendar answers for. */
    @Nonnull
    public FixedCalendar season(@Nonnull String eventId, @Nonnull Dates dates) {
        seasons.put(AlmanacKeys.normalize(eventId), dates);
        return this;
    }

    @Override
    @Nullable
    public SeasonState state(@Nonnull String eventId) {
        Dates dates = seasons.get(AlmanacKeys.normalize(eventId));
        if (dates == null) {
            return null;
        }
        return dates.live() == null ? SeasonState.BETWEEN : SeasonState.liveIn(dates.live().year());
    }

    @Override
    @Nonnull
    public Dates dates(@Nonnull String eventId, long nowMs) {
        Dates dates = seasons.get(AlmanacKeys.normalize(eventId));
        return dates == null ? Dates.UNKNOWN : dates;
    }

    /** Noon of {@code isoDate} in {@code zone}, as epoch milliseconds: a moment well inside that day. */
    public static long noon(@Nonnull String isoDate, @Nonnull ZoneId zone) {
        return LocalDate.parse(isoDate).atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
    }

    /** Noon of {@code isoDate} in UTC. */
    public static long noon(@Nonnull String isoDate) {
        return noon(isoDate, UTC);
    }

    /**
     * A run from the start of {@code firstDay} to the end of {@code lastDay} (both days in), counted in
     * {@code zone}, as the calendar answers one: its end is the midnight after its last day.
     */
    @Nonnull
    public static Occurrence run(@Nonnull String eventId, int year, @Nonnull String firstDay,
            @Nonnull String lastDay, @Nonnull ZoneId zone) {
        long start = LocalDate.parse(firstDay).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = LocalDate.parse(lastDay).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new Occurrence(eventId, year, start, end);
    }

    /** The fixture season's run of {@code year}: October 1 to November 3, in UTC. */
    @Nonnull
    public static Occurrence autumn(int year) {
        return run(TEST_SEASON, year, year + "-10-01", year + "-11-03", UTC);
    }

    /** What the calendar says of a season on now, in UTC. */
    @Nonnull
    public static Dates liveIn(int year, int firstYear) {
        return new Dates(autumn(year), autumn(year + 1), history(firstYear, year), firstYear, UTC);
    }

    /** What the calendar says of a season between runs, its last run in {@code lastYear}, in UTC. */
    @Nonnull
    public static Dates betweenAfter(int lastYear, int firstYear) {
        return new Dates(null, autumn(lastYear + 1), history(firstYear, lastYear), firstYear, UTC);
    }

    /** Every run of the fixture season from {@code from} to {@code to}, oldest first. */
    @Nonnull
    public static List<Occurrence> history(int from, int to) {
        ArrayList<Occurrence> runs = new ArrayList<>();
        for (int year = from; year <= to; year++) {
            runs.add(autumn(year));
        }
        return List.copyOf(runs);
    }
}
