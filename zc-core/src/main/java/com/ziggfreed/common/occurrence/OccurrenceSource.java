package com.ziggfreed.common.occurrence;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a module that never sees the calendar may ask about recurring events. A run is (event, year, number):
 * {@link Occurrence#number} names it within its year, so an event may come round several times a year, and
 * every answer about runs names one. Every answer that depends on the time takes the asker's own clock
 * reading, so an engine running on an injected clock asks with that clock. Every answer is cheap and safe
 * from any thread, so a reader may ask on every availability read.
 *
 * <p>An event this server does not have, or one its owner switched off, is ABSENT: it is not
 * enabled, never live, and has no history. Off means absent, never locked.
 *
 * <p>The two YEAR questions and the clock are the exceptions: {@link #firstYear}, {@link #currentYear}
 * and {@link #zone} answer for any event LOADED on this server whatever its switches say, so a reader
 * that keeps what a player earned in a past run (a yearly trophy) still knows its years after the owner
 * switches the event off. So does {@link #dated}, so a reader holding a run knows when its own days end.
 */
public interface OccurrenceSource {

    /** Nothing is known: every event is absent and no event has a year. What a server with no calendar answers. */
    OccurrenceSource NONE = new OccurrenceSource() {
        @Override
        public boolean isEnabled(@Nonnull String eventId) {
            return false;
        }

        @Override
        @Nullable
        public Occurrence live(@Nonnull String eventId, long nowMs) {
            return null;
        }

        @Override
        @Nonnull
        public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
            return List.of();
        }
    };

    /** Is {@code eventId} on this server and switched on, whether or not it is running now? */
    boolean isEnabled(@Nonnull String eventId);

    /** The run of {@code eventId} going on at {@code nowMs}, with its year and number, or null when none is. */
    @Nullable
    Occurrence live(@Nonnull String eventId, long nowMs);

    /**
     * Every run of {@code eventId} that has begun by {@code nowMs}, from the event's first year on, in order
     * ({@link Occurrence#IN_ORDER}); a run going on now is the last entry, and a forced run is listed once.
     * Empty for an absent event.
     */
    @Nonnull
    List<Occurrence> history(@Nonnull String eventId, long nowMs);

    /**
     * The first year {@code eventId} runs (its file's {@code FirstYear}), for any event LOADED on this
     * server whatever its switches say. Null when no event of that id is loaded, or its file states no
     * first year or one outside 1970 to 9999 (such an event never runs, so there is no year to count
     * from). The default answers null, as a source that knows no years does.
     */
    @Nullable
    default Integer firstYear(@Nonnull String eventId) {
        return null;
    }

    /**
     * The year {@code eventId} is in at {@code nowMs}, for any event LOADED on this server whatever its
     * switches say: the year the run going on STARTED in (the run {@link #live} would answer were the
     * event switched on, by its dates or a force), else the calendar year {@code nowMs} falls in, both
     * counted in the event's own clock. So a run crossing the new year answers its December's year in
     * early January, and whenever {@link #live} answers a run, this answers that run's year. Every run of a
     * year answers that year, so one yearly copy stands for all of them. It can come before
     * {@link #firstYear} while the first run is still ahead. Null when no event of that id is loaded. The
     * default answers null, as a source that knows no years does.
     *
     * <p>This is the year a yearly copy is minted for: a reader keeps one copy per year from
     * {@link #firstYear} through this one (and may mint the next ahead), so the run going on always has
     * its copy.
     */
    @Nullable
    default Integer currentYear(@Nonnull String eventId, long nowMs) {
        return null;
    }

    /**
     * The first run of {@code eventId} to start after {@code nowMs} (a run's first instant is already
     * inside it), never one before its first year and never the run {@link #live} answers nor one before it
     * ({@link Occurrence#IN_ORDER}): while a run goes on, the next is the one after it. Forces count as
     * {@link #live} counts them: a run forced on ahead of its dates is already going on, so the next is the
     * run after it; a run forced off returns when its dates next come round. Null when the event is absent,
     * and from a source that knows no dates (the default).
     */
    @Nullable
    default Occurrence next(@Nonnull String eventId, long nowMs) {
        return null;
    }

    /**
     * The run of {@code eventId} that comes after run {@code number} of {@code year} by its dates: that year's
     * next run, else the first run of the next year that has one; forces aside. A number the year does not
     * have is followed from where it would fall. A reader whose player has spent the run {@link #next}
     * answers (an owner may move a spent run later than now) asks this for the run after it, and so on past
     * every run the player has spent, to name when they are next offered something.
     * Null when the event is absent or no run is left, and from a source that knows no dates (the default).
     */
    @Nullable
    default Occurrence after(@Nonnull String eventId, int year, int number) {
        return null;
    }

    /**
     * Run {@code number} of {@code year} of {@code eventId} on its days as the event's dates read now, wherever an
     * owner moved them, for any event LOADED on this server whatever its switches and forces say: a run switched
     * off or forced off keeps its days. A reader holding a run by identity asks this when that run's own days end.
     * Null when no event of that id is loaded or it cannot run, the year has no such run (set aside, skipped,
     * before the first year or never dated), and from a source that knows no dates (the default).
     */
    @Nullable
    default Occurrence dated(@Nonnull String eventId, int year, int number) {
        return null;
    }

    /**
     * The clock {@code eventId}'s days are counted in: every run starts at this zone's midnight and its
     * year is this zone's year, so a reader counting the days left or naming a run's first and last day
     * counts in this zone. Answers for any event LOADED on this server whatever its switches say. UTC for
     * an event the source does not know or one that names no clock, and from a source that knows no
     * clocks (the default).
     */
    @Nonnull
    default ZoneId zone(@Nonnull String eventId) {
        return ZoneOffset.UTC;
    }

    /**
     * Do {@code eventId}'s days move from year to year (a rule such as Easter or the Nth weekday of a month,
     * or days set for particular years), or does it come round several times a year? Either way no one run's
     * days stand for every year. A reader that would call the days "every year" asks this first and
     * names the run's own year instead when they move. Answers for any event LOADED whatever its switches
     * say; false for an event the source does not know, and from a source that knows no dates (the default).
     */
    default boolean datesMove(@Nonnull String eventId) {
        return false;
    }

    /**
     * How {@code eventId} comes round when it runs monthly or weekly: the rule dating the year it is in at
     * {@code nowMs} (its first year's, before that), for a reader that says "the first Sunday of every month"
     * rather than one run's days. Null for an event that comes round once a year or less, for a year whose days
     * the event sets out itself, for an event the source does not know, and from a source that knows no rules (the
     * default). Answers for any event LOADED whatever its switches say, as {@link #datesMove} does.
     */
    @Nullable
    default Recurrence recurrence(@Nonnull String eventId, long nowMs) {
        return null;
    }
}
