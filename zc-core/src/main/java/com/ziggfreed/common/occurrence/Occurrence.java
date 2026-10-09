package com.ziggfreed.common.occurrence;

import java.util.Comparator;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One run of a recurring event: which event, the year the run belongs to, its number within that year, and
 * when it starts and stops, in epoch milliseconds.
 *
 * <p>The YEAR is the year the run STARTS in, so a run from 12-20 to 01-05 belongs to the year of its
 * December. The NUMBER names the run within its year, from 1, as the event's dates give it: an event that
 * comes round once a year only ever has run 1, and one that comes round several times a year numbers each
 * run by what it is (a monthly run by its month, a weekly run by its week of the year, one of several spans
 * by its place in the list). So a number is a name, never a count: a year's numbers may skip, and need not
 * follow the dates. A run is (event, year, number) wherever it is kept or compared ({@link #sameRun}), and
 * runs come round in the order they start ({@link #IN_ORDER}). {@code startMs} is the run's first instant and
 * {@code endMs} the first instant after it, so {@link #contains} is half-open: a run is over the moment its
 * end arrives.
 *
 * @param eventId the event's id, lower-cased (the form every id in the library compares in)
 * @param number  the run's number within its year, 1 or more
 */
public record Occurrence(@Nonnull String eventId, int year, int number, long startMs, long endMs) {

    /**
     * Runs in the order they come round: by year, then by start (then by number, for a tie). Never by number
     * alone: a year's numbers need not follow its dates.
     */
    public static final Comparator<Occurrence> IN_ORDER = Comparator.comparingInt(Occurrence::year)
            .thenComparingLong(Occurrence::startMs).thenComparingInt(Occurrence::number);

    /**
     * The highest number a run takes: a monthly run's month is 1 to 12, a weekly run's calendar week 1 to 54, and
     * a list of spans holds at most this many (the calendar reads a longer list as no days at all). So a reader
     * may keep a year's runs as a set of numbers 1 to 54, as a once-a-run quest's record keeps the runs it spent.
     */
    public static final int MAX_NUMBER = 54;

    public Occurrence {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("an occurrence names its event");
        }
        if (number < 1) {
            throw new IllegalArgumentException("a run's number counts from 1: " + number);
        }
        if (endMs <= startMs) {
            throw new IllegalArgumentException("an occurrence ends after it starts: " + startMs + " .. " + endMs);
        }
        eventId = eventId.trim().toLowerCase(Locale.ROOT);
    }

    /** The year's run 1: what every event that comes round once a year has. */
    public Occurrence(@Nonnull String eventId, int year, long startMs, long endMs) {
        this(eventId, year, 1, startMs, endMs);
    }

    /** Is {@code nowMs} inside this run? The start is in, the end is out. */
    public boolean contains(long nowMs) {
        return nowMs >= startMs && nowMs < endMs;
    }

    /** Is {@code other} the same run: the same event, year and number, wherever its days now fall? */
    public boolean sameRun(@Nullable Occurrence other) {
        return other != null && year == other.year && number == other.number && eventId.equals(other.eventId);
    }

    /**
     * {@code 2026} for run 1, {@code 2026#9} for run 9 of the year: the run as data, the same in every language,
     * for a log line or an admin answer. Never player text: a number names a run, it does not count runs.
     */
    @Nonnull
    public String label() {
        return number == 1 ? Integer.toString(year) : year + "#" + number;
    }
}
