package com.ziggfreed.common.calendar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import javax.annotation.Nonnull;

/**
 * When one run is: from {@code start} up to, not including, {@code end}, both on the event's own clock (a wall
 * clock, read in the event's zone). A run of whole days starts at a midnight and ends at the midnight after its
 * last day; a run with a time of day starts then and lasts its length. The run belongs to the year its start
 * falls in; its end may fall in the next.
 */
public record RunDays(@Nonnull LocalDateTime start, @Nonnull LocalDateTime end) {

    public RunDays {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("a run ends after it starts: " + start + " .. " + end);
        }
    }

    /** Whole days, both in: from the first day's midnight to the midnight after the last. */
    public RunDays(@Nonnull LocalDate first, @Nonnull LocalDate last) {
        this(first.atStartOfDay(), last.plusDays(1).atStartOfDay());
    }

    /** The day the run starts on. */
    @Nonnull
    public LocalDate first() {
        return start.toLocalDate();
    }

    /** The day the run's last instant falls on. */
    @Nonnull
    public LocalDate last() {
        return end.minusNanos(1).toLocalDate();
    }

    /** How many days the run touches, both ends counted. */
    public long length() {
        return ChronoUnit.DAYS.between(first(), last()) + 1L;
    }

    /**
     * Do the two runs share an instant, whichever starts first? A run that ends the very instant the other starts
     * does not meet it. For {@code other} starting no later, that is: does this run start before {@code other} ends?
     */
    public boolean meets(@Nonnull RunDays other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    /** The run's first instant, in {@code zone}. */
    public long startMs(@Nonnull ZoneId zone) {
        return start.atZone(zone).toInstant().toEpochMilli();
    }

    /** The first instant after the run, in {@code zone}. */
    public long endMs(@Nonnull ZoneId zone) {
        return end.atZone(zone).toInstant().toEpochMilli();
    }
}
