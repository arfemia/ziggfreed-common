package com.ziggfreed.common.occurrence;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a module that never sees the calendar may ask about recurring events. Every answer that depends
 * on the time takes the asker's own clock reading, so an engine running on an injected clock asks with
 * that clock. Every answer is cheap and safe from any thread, so a reader may ask on every availability
 * read.
 *
 * <p>An event this server does not have, or one its owner switched off, is ABSENT: it is not
 * enabled, never live, and has no history. Off means absent, never locked.
 *
 * <p>The two YEAR questions are the one exception: {@link #firstYear} and {@link #currentYear} answer
 * for any event LOADED on this server whatever its switches say, so a reader that keeps what a player
 * earned in a past run (a yearly trophy) still knows its years after the owner switches the event off.
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

    /** The run of {@code eventId} going on at {@code nowMs}, or null when none is. */
    @Nullable
    Occurrence live(@Nonnull String eventId, long nowMs);

    /**
     * Every run of {@code eventId} that has begun by {@code nowMs}, oldest first, from the event's
     * first year on; a run going on now is the last entry. Empty for an absent event.
     */
    @Nonnull
    List<Occurrence> history(@Nonnull String eventId, long nowMs);

    /**
     * The first year {@code eventId} runs (its file's {@code FirstYear}), for any event LOADED on this
     * server whatever its switches say. Null when no event of that id is loaded, or its file states no
     * first year. The default answers null, as a source that knows no years does.
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
     * early January, and whenever {@link #live} answers a run, this answers that run's year. It can come
     * before {@link #firstYear} while the first run is still ahead. Null when no event of that id is
     * loaded. The default answers null, as a source that knows no years does.
     *
     * <p>This is the year a yearly copy is minted for: a reader keeps one copy per year from
     * {@link #firstYear} through this one (and may mint the next ahead), so the run going on always has
     * its copy.
     */
    @Nullable
    default Integer currentYear(@Nonnull String eventId, long nowMs) {
        return null;
    }
}
