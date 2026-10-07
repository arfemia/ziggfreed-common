package com.ziggfreed.common.almanac;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;

/**
 * The calendar, read through zc-core's occurrence slot ({@link Occurrences}), which the calendar module
 * fills at setup, so the Almanac needs no edge to the module that runs it.
 *
 * <p>Silence is absence: an event the source does not have switched on, an unfilled slot and a throwing
 * source all read as "no such season", so a server without a calendar shows no seasons rather than an
 * error. An event switched on with no run going on (between its dates, or stopped by a command) is
 * between seasons and stays listed; a run forced on is live in the year it is filed under.
 */
public final class OccurrenceAlmanacCalendar implements AlmanacCalendar {

    /** The production calendar: whatever filled the slot, read at the moment of asking, on the system clock. */
    public static final OccurrenceAlmanacCalendar INSTANCE =
            new OccurrenceAlmanacCalendar(Occurrences::source, System::currentTimeMillis);

    private final Supplier<OccurrenceSource> source;
    private final LongSupplier clock;

    public OccurrenceAlmanacCalendar(@Nonnull Supplier<OccurrenceSource> source, @Nonnull LongSupplier clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    @Nullable
    public SeasonState state(@Nonnull String eventId) {
        try {
            OccurrenceSource occurrences = source.get();
            if (!occurrences.isEnabled(eventId)) {
                return null;
            }
            Occurrence run = occurrences.live(eventId, clock.getAsLong());
            return run == null ? SeasonState.BETWEEN : SeasonState.liveIn(run.year());
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    @Nonnull
    public Dates dates(@Nonnull String eventId, long nowMs) {
        try {
            OccurrenceSource occurrences = source.get();
            if (!occurrences.isEnabled(eventId)) {
                return Dates.UNKNOWN;
            }
            return new Dates(occurrences.live(eventId, nowMs), occurrences.next(eventId, nowMs),
                    occurrences.history(eventId, nowMs), occurrences.firstYear(eventId), occurrences.zone(eventId),
                    occurrences.datesMove(eventId));
        } catch (Throwable t) {
            return Dates.UNKNOWN;
        }
    }
}
