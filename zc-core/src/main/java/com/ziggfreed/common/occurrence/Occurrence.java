package com.ziggfreed.common.occurrence;

import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * One run of a recurring event: which event, the year the run belongs to, and when it starts and
 * stops, in epoch milliseconds.
 *
 * <p>The YEAR is the year the run STARTS in, so a run from 12-20 to 01-05 belongs to the year of its
 * December. {@code startMs} is the run's first instant and {@code endMs} the first instant after it,
 * so {@link #contains} is half-open: a run is over the moment its end arrives.
 *
 * @param eventId the event's id, lower-cased (the form every id in the library compares in)
 */
public record Occurrence(@Nonnull String eventId, int year, long startMs, long endMs) {

    public Occurrence {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("an occurrence names its event");
        }
        if (endMs <= startMs) {
            throw new IllegalArgumentException("an occurrence ends after it starts: " + startMs + " .. " + endMs);
        }
        eventId = eventId.trim().toLowerCase(Locale.ROOT);
    }

    /** Is {@code nowMs} inside this run? The start is in, the end is out. */
    public boolean contains(long nowMs) {
        return nowMs >= startMs && nowMs < endMs;
    }
}
