package com.ziggfreed.common.calendar.tick;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.ziggfreed.common.occurrence.Occurrence;

/**
 * One look at the calendar: what is running now ({@code live}, event ids) and what began or ended since
 * the last look. {@code booting} marks the first look after the server booted. An event live by a condition (a
 * Moon or a Live) has no runs, so it is never in {@code live}, {@code started} or {@code ended}: it has its own
 * three, {@code conditions} (live now), {@code rose} (live since the last look) and {@code lapsed} (no longer).
 */
public record CalendarTick(long nowMs, boolean booting, @Nonnull Set<String> live,
                           @Nonnull List<Started> started, @Nonnull List<Ended> ended,
                           @Nonnull Set<String> conditions, @Nonnull List<String> rose,
                           @Nonnull List<Lapsed> lapsed) {

    public CalendarTick {
        live = Set.copyOf(live);
        started = List.copyOf(started);
        ended = List.copyOf(ended);
        conditions = Set.copyOf(conditions);
        rose = List.copyOf(rose);
        lapsed = List.copyOf(lapsed);
    }

    /** A look that saw no condition rise or lapse. */
    public CalendarTick(long nowMs, boolean booting, @Nonnull Set<String> live, @Nonnull List<Started> started,
            @Nonnull List<Ended> ended) {
        this(nowMs, booting, live, started, ended, Set.of(), List.of(), List.of());
    }

    /** Did anything begin or end, a run or a condition? */
    public boolean changed() {
        return !started.isEmpty() || !ended.isEmpty() || !rose.isEmpty() || !lapsed.isEmpty();
    }

    /** A run began; {@code resumed} when the server booted with it already going. */
    public record Started(@Nonnull Occurrence occurrence, boolean resumed) {
    }

    /** A run is over; {@code switchedOff} when its event was switched off rather than its run ending. */
    public record Ended(@Nonnull Occurrence occurrence, boolean switchedOff) {
    }

    /**
     * An event live by a condition is no longer live; {@code switchedOff} when it was switched off, rather than its
     * condition ceasing to hold or a command stopping it.
     */
    public record Lapsed(@Nonnull String eventId, boolean switchedOff) {
    }
}
