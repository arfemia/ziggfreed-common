package com.ziggfreed.common.achievement.asset;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/**
 * A calendar a test drives by hand, answering as zc-core's occurrence source does: which events are
 * loaded, from which year, which year each is in, which run of each is going on right now (one at a
 * time, as a calendar has), and which an owner switched off (absent to every run question while its
 * years stay known). It ignores the clock reading it is asked with. Mutable on purpose, so a test can
 * open, close or switch off an occurrence AFTER the fold and prove the folded achievement reads it
 * live.
 */
final class FakeCalendar implements OccurrenceSource {

    private final Map<String, int[]> years = new HashMap<>();
    private final Map<String, Integer> running = new HashMap<>();
    private final Set<String> switchedOff = new HashSet<>();
    private boolean throwing;
    private boolean throwingAtFold;

    /** A loaded event: the year it first runs, and the year it is in now. */
    @Nonnull
    FakeCalendar event(@Nonnull String eventId, int firstYear, int currentYear) {
        years.put(key(eventId), new int[] {firstYear, currentYear});
        return this;
    }

    /** The run that started in {@code year} is going on now, in place of any other run of the event. */
    @Nonnull
    FakeCalendar live(@Nonnull String eventId, int year) {
        running.put(key(eventId), year);
        return this;
    }

    /** That year's run is over. */
    @Nonnull
    FakeCalendar ended(@Nonnull String eventId, int year) {
        running.remove(key(eventId), year);
        return this;
    }

    /** The owner switched the event off: absent to every run question, its years still known. */
    @Nonnull
    FakeCalendar switchedOff(@Nonnull String eventId) {
        switchedOff.add(key(eventId));
        return this;
    }

    /** The owner switched the event back on. */
    @Nonnull
    FakeCalendar switchedOn(@Nonnull String eventId) {
        switchedOff.remove(key(eventId));
        return this;
    }

    /** Every run question throws, as a broken calendar would on a live read. */
    @Nonnull
    FakeCalendar throwing() {
        throwing = true;
        return this;
    }

    /** Every year question throws, as a broken calendar would at the fold. */
    @Nonnull
    FakeCalendar throwingAtFold() {
        throwingAtFold = true;
        return this;
    }

    /** This calendar behind the reader a fold asks, on a clock the fixture ignores. */
    @Nonnull
    OccurrenceReader reader() {
        return new OccurrenceReader(() -> this, () -> 0L);
    }

    @Override
    public boolean isEnabled(@Nonnull String eventId) {
        String key = key(eventId);
        return years.containsKey(key) && !switchedOff.contains(key);
    }

    @Override
    @Nullable
    public Occurrence live(@Nonnull String eventId, long nowMs) {
        if (throwing) {
            throw new IllegalStateException("the fixture calendar is down");
        }
        String key = key(eventId);
        Integer year = switchedOff.contains(key) ? null : running.get(key);
        return year == null ? null : new Occurrence(key, year, 0L, Long.MAX_VALUE);
    }

    /** This module never asks for the past; the run going on, if any, is all the fixture knows. */
    @Override
    @Nonnull
    public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
        Occurrence run = live(eventId, nowMs);
        return run == null ? List.of() : List.of(run);
    }

    @Override
    @Nullable
    public Integer firstYear(@Nonnull String eventId) {
        if (throwingAtFold) {
            throw new IllegalStateException("the fixture calendar is down");
        }
        int[] known = years.get(key(eventId));
        return known == null ? null : known[0];
    }

    @Override
    @Nullable
    public Integer currentYear(@Nonnull String eventId, long nowMs) {
        if (throwingAtFold) {
            throw new IllegalStateException("the fixture calendar is down");
        }
        int[] known = years.get(key(eventId));
        return known == null ? null : known[1];
    }

    @Nonnull
    private static String key(@Nonnull String eventId) {
        return eventId.trim().toLowerCase(Locale.ROOT);
    }
}
