package com.ziggfreed.common.quest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/**
 * One calendar event a test dates by hand, answering as zc-core's occurrence source does: each year's
 * run has its own days (a test may move them mid-run, as an owner's file does), a force on runs the
 * current year's run with that year's days, a force off stops it, and the next run is the first one
 * after the run going on. UTC throughout; both days of a run are in it.
 */
final class FakeRuns implements OccurrenceSource {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long DAY_MS = 86_400_000L;

    private final String eventId;
    private final TreeMap<Integer, long[]> runs = new TreeMap<>();
    @Nullable private Boolean forced;

    FakeRuns(@Nonnull String eventId) {
        this.eventId = eventId.trim().toLowerCase(Locale.ROOT);
    }

    /** The run that starts in {@code year}, {@code firstDay} through {@code lastDay} (ISO dates); replaces that year's days. */
    @Nonnull
    FakeRuns run(int year, @Nonnull String firstDay, @Nonnull String lastDay) {
        runs.put(year, new long[] {midnight(firstDay), midnight(lastDay) + DAY_MS});
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

    private boolean mine(@Nonnull String asked) {
        return eventId.equals(asked.trim().toLowerCase(Locale.ROOT));
    }

    @Nonnull
    private Occurrence occurrence(int year) {
        long[] days = runs.get(year);
        return new Occurrence(eventId, year, days[0], days[1]);
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
        for (Map.Entry<Integer, long[]> run : runs.entrySet()) {
            if (nowMs >= run.getValue()[0] && nowMs < run.getValue()[1]) {
                return occurrence(run.getKey());
            }
        }
        if (Boolean.TRUE.equals(forced)) {
            int year = Instant.ofEpochMilli(nowMs).atZone(UTC).getYear();
            return runs.containsKey(year) ? occurrence(year) : null;
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
        for (Map.Entry<Integer, long[]> run : runs.entrySet()) {
            if (run.getValue()[0] <= nowMs) {
                out.add(occurrence(run.getKey()));
            }
        }
        Occurrence running = live(asked, nowMs);
        if (running != null && out.stream().noneMatch(run -> run.year() == running.year())) {
            out.add(running);
            out.sort(Comparator.comparingInt(Occurrence::year));
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
        for (Map.Entry<Integer, long[]> run : runs.entrySet()) {
            if (run.getValue()[0] > nowMs && (running == null || run.getKey() > running.year())) {
                return occurrence(run.getKey());
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
