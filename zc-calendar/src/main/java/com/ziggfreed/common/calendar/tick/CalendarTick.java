package com.ziggfreed.common.calendar.tick;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.ziggfreed.common.occurrence.Occurrence;

/**
 * One look at the calendar: what is running now ({@code live}, event ids) and what began or ended since
 * the last look. {@code booting} marks the first look after the server booted.
 */
public record CalendarTick(long nowMs, boolean booting, @Nonnull Set<String> live,
                           @Nonnull List<Started> started, @Nonnull List<Ended> ended) {

    public CalendarTick {
        live = Set.copyOf(live);
        started = List.copyOf(started);
        ended = List.copyOf(ended);
    }

    /** Did anything begin or end? */
    public boolean changed() {
        return !started.isEmpty() || !ended.isEmpty();
    }

    /** A run began; {@code resumed} when the server booted with it already going. */
    public record Started(@Nonnull Occurrence occurrence, boolean resumed) {
    }

    /** A run is over; {@code switchedOff} when its event was switched off rather than its run ending. */
    public record Ended(@Nonnull Occurrence occurrence, boolean switchedOff) {
    }
}
