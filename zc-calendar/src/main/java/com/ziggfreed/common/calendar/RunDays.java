package com.ziggfreed.common.calendar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import javax.annotation.Nonnull;

/**
 * The first and the last day of one run, both in it. The last may fall in the next year (a run crossing
 * the new year); the run still belongs to the year of its first day.
 */
public record RunDays(@Nonnull LocalDate first, @Nonnull LocalDate last) {

    public RunDays {
        if (first == null || last == null || last.isBefore(first)) {
            throw new IllegalArgumentException("a run ends on or after the day it starts: " + first + " .. " + last);
        }
    }

    /** How many days the run lasts, both ends counted. */
    public long length() {
        return ChronoUnit.DAYS.between(first, last) + 1L;
    }
}
