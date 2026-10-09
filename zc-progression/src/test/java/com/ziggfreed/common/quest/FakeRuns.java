package com.ziggfreed.common.quest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/**
 * One calendar event a test dates by hand, answering as zc-core's occurrence source does. Each run, (year,
 * number), has its own days, and a test may move them mid-run as an owner's file does. A number names a run and
 * need not follow its days, so every question of time order reads the runs by start ({@link Occurrence#IN_ORDER}).
 * A force on runs the current year's first run not yet over, else its last, with its own days. A force off stops
 * it. The next run is the first one after the run going on. A run the year set aside (it met another) keeps its
 * days for {@link #after} alone, which follows it from its own start as the calendar does; it never runs. UTC
 * throughout; both days of a run are in it.
 */
final class FakeRuns implements OccurrenceSource {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long DAY_MS = 86_400_000L;

    private final String eventId;
    /** Each kept run's [start, end) by year, then number. */
    private final TreeMap<Integer, TreeMap<Integer, long[]>> runs = new TreeMap<>();
    /** Each set-aside run's [start, end) by year, then number: no run, only a place to follow from. */
    private final TreeMap<Integer, TreeMap<Integer, long[]>> setAside = new TreeMap<>();
    @Nullable private Boolean forced;

    FakeRuns(@Nonnull String eventId) {
        this.eventId = eventId.trim().toLowerCase(Locale.ROOT);
    }

    /** The year's first run, {@code firstDay} through {@code lastDay} (ISO dates); replaces its days. */
    @Nonnull
    FakeRuns run(int year, @Nonnull String firstDay, @Nonnull String lastDay) {
        return run(year, 1, firstDay, lastDay);
    }

    /** Run {@code number} of {@code year}, {@code firstDay} through {@code lastDay}; replaces its days. */
    @Nonnull
    FakeRuns run(int year, int number, @Nonnull String firstDay, @Nonnull String lastDay) {
        days(setAside, year).remove(number);
        days(runs, year).put(number, new long[] {midnight(firstDay), midnight(lastDay) + DAY_MS});
        return this;
    }

    /**
     * Run {@code number} of {@code year} moved to {@code firstDay} through {@code lastDay}, where it meets a run
     * written before it, so the year sets it aside: it never runs, and {@link #after} follows it from its own start.
     */
    @Nonnull
    FakeRuns setAside(int year, int number, @Nonnull String firstDay, @Nonnull String lastDay) {
        days(runs, year).remove(number);
        days(setAside, year).put(number, new long[] {midnight(firstDay), midnight(lastDay) + DAY_MS});
        return this;
    }

    /** Force the event on ({@code true}), off ({@code false}) or back to its days ({@code null}). */
    @Nonnull
    FakeRuns force(@Nullable Boolean running) {
        forced = running;
        return this;
    }

    /** An ISO instant in epoch milliseconds. */
    static long at(@Nonnull String isoInstant) {
        return Instant.parse(isoInstant).toEpochMilli();
    }

    private static long midnight(@Nonnull String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(UTC).toInstant().toEpochMilli();
    }

    @Nonnull
    private static TreeMap<Integer, long[]> days(@Nonnull TreeMap<Integer, TreeMap<Integer, long[]>> table, int year) {
        return table.computeIfAbsent(year, y -> new TreeMap<>());
    }

    private boolean mine(@Nonnull String asked) {
        return eventId.equals(asked.trim().toLowerCase(Locale.ROOT));
    }

    /** Every kept run, in the order they come round. */
    @Nonnull
    private List<Occurrence> all() {
        List<Occurrence> out = new ArrayList<>();
        for (Map.Entry<Integer, TreeMap<Integer, long[]>> year : runs.entrySet()) {
            for (Map.Entry<Integer, long[]> run : year.getValue().entrySet()) {
                out.add(new Occurrence(eventId, year.getKey(), run.getKey(), run.getValue()[0], run.getValue()[1]));
            }
        }
        out.sort(Occurrence.IN_ORDER);
        return out;
    }

    @Override
    public boolean isEnabled(@Nonnull String asked) {
        return mine(asked);
    }

    @Override
    @Nullable
    public Occurrence live(@Nonnull String asked, long nowMs) {
        if (!mine(asked) || Boolean.FALSE.equals(forced)) {
            return null;
        }
        for (Occurrence run : all()) {
            if (run.contains(nowMs)) {
                return run;
            }
        }
        if (Boolean.TRUE.equals(forced)) {
            int year = Instant.ofEpochMilli(nowMs).atZone(UTC).getYear();
            Occurrence last = null;
            for (Occurrence run : all()) {
                if (run.year() != year) {
                    continue;
                }
                if (run.endMs() > nowMs) {
                    return run;
                }
                last = run;
            }
            return last;
        }
        return null;
    }

    @Override
    @Nonnull
    public List<Occurrence> history(@Nonnull String asked, long nowMs) {
        List<Occurrence> out = new ArrayList<>();
        if (!mine(asked)) {
            return out;
        }
        for (Occurrence run : all()) {
            if (run.startMs() <= nowMs) {
                out.add(run);
            }
        }
        Occurrence running = live(asked, nowMs);
        if (running != null && out.stream().noneMatch(running::sameRun)) {
            out.add(running);
            out.sort(Occurrence.IN_ORDER);
        }
        return out;
    }

    @Override
    @Nullable
    public Occurrence next(@Nonnull String asked, long nowMs) {
        if (!mine(asked)) {
            return null;
        }
        Occurrence running = live(asked, nowMs);
        for (Occurrence run : all()) {
            if (run.startMs() > nowMs && (running == null || Occurrence.IN_ORDER.compare(run, running) > 0)) {
                return run;
            }
        }
        return null;
    }

    /**
     * The run after run {@code number} of {@code year} in time, never by number: the year's first kept run to
     * start after that run's own start (kept or set aside), else the next year's earliest; forces aside. A number
     * the year lacks is followed by the next year's earliest run, as a list of spans is.
     */
    @Override
    @Nullable
    public Occurrence after(@Nonnull String asked, int year, int number) {
        if (!mine(asked)) {
            return null;
        }
        long[] own = days(runs, year).get(number);
        if (own == null) {
            own = days(setAside, year).get(number);
        }
        for (Occurrence run : all()) {
            if (run.year() > year || (own != null && run.year() == year && run.startMs() > own[0])) {
                return run;
            }
        }
        return null;
    }

    @Override
    @Nonnull
    public ZoneId zone(@Nonnull String asked) {
        return UTC;
    }
}
