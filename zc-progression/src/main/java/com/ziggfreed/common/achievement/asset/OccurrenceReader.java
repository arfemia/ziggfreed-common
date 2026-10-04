package com.ziggfreed.common.achievement.asset;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;

/**
 * The three questions a yearly achievement asks a calendar, answered from zc-core's one occurrence
 * slot ({@link Occurrences}), which the calendar module fills at library setup; this module never
 * sees that module.
 *
 * <p>The two YEAR questions look past the owner's switches ({@link OccurrenceSource#firstYear},
 * {@link OccurrenceSource#currentYear}), so an event switched off keeps every yearly copy, and what a
 * player earned of it stays theirs. The RUN question does not: {@link #isLive} holds only while the
 * source answers a run going on ({@link OccurrenceSource#live}, which reads a switched-off event as
 * absent) and that run is the asked year's, so nothing counts toward an event that is off. Every
 * answer is cheap and safe from any thread by the slot's contract, which is what lets {@link #isLive}
 * be asked on every availability read.
 *
 * <p>Each question reads the slot and the clock afresh: the slot is filled at setup, after this class
 * may have loaded, so a source kept in a field would read "no calendar" for the life of the server.
 */
final class OccurrenceReader {

    /** What every production fold reads: zc-core's slot as it is filled now, on the wall clock. */
    static final OccurrenceReader LIVE = new OccurrenceReader(Occurrences::source, System::currentTimeMillis);

    private final Supplier<OccurrenceSource> source;
    private final LongSupplier clock;

    /** A reader over {@code source}, asking with {@code clock}'s reading; both are asked on every call. */
    OccurrenceReader(@Nonnull Supplier<OccurrenceSource> source, @Nonnull LongSupplier clock) {
        this.source = source;
        this.clock = clock;
    }

    /** The year the event first runs, switched on or not; null when no loaded event answers to the id. */
    @Nullable
    Integer firstYear(@Nonnull String eventId) {
        return source.get().firstYear(eventId);
    }

    /**
     * The year the event is in now, switched on or not: the year a run going on started in, else the
     * calendar year in the event's own clock; null when no loaded event answers to the id.
     */
    @Nullable
    Integer currentYear(@Nonnull String eventId) {
        return source.get().currentYear(eventId, clock.getAsLong());
    }

    /** Is the run that started in {@code year} going on now? Never for an event that is switched off. */
    boolean isLive(@Nonnull String eventId, int year) {
        Occurrence run = source.get().live(eventId, clock.getAsLong());
        return run != null && run.year() == year;
    }
}
