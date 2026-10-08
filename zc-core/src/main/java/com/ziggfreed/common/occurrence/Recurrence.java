package com.ziggfreed.common.occurrence;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Month;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How an event that comes round monthly or weekly recurs, as its authored rule says it: enough for a reader that
 * never sees the calendar to say it in words ("the first Sunday of every month, for 7 days"), never to date a run
 * (the source's {@link OccurrenceSource#next} and {@link OccurrenceSource#history} do that). A value describing a
 * rule, not a mode: {@link Monthly} or {@link Weekly}, each with how often it comes round ({@link #every}), the
 * months it runs in ({@link #months}, none named for every month) and how long each run lasts ({@link Length}).
 * Each record keeps only what its rule means, so two ways of writing one rule are one value.
 */
public sealed interface Recurrence permits Recurrence.Monthly, Recurrence.Weekly {

    /** {@code nth} for the last such weekday of the month. */
    int LAST = -1;

    /** How many months or weeks apart the runs are: 1 for every one, 2 for every other, at least 1. */
    int every();

    /** The months a run starts in, January first; empty when it runs in every month. Unmodifiable. */
    @Nonnull
    Set<Month> months();

    /** How long each run lasts. */
    @Nonnull
    Length length();

    /** {@code months} as an unmodifiable set iterated January first; empty for none, null or all twelve. */
    @Nonnull
    private static Set<Month> named(@Nullable Set<Month> months) {
        if (months == null || months.isEmpty() || months.size() == Month.values().length) {
            return Set.of();
        }
        EnumSet<Month> ordered = EnumSet.noneOf(Month.class);
        ordered.addAll(months);
        return Collections.unmodifiableSet(ordered);
    }

    /**
     * How long each run lasts, from its first day: until the next run starts ({@code untilNext}), else
     * {@code duration} from its time of day, else {@code days} whole days from it. The first of those a rule names
     * wins and the others are not kept. {@code at} is the time of day a run starts, null for midnight.
     *
     * @param days     whole days, 1 or more; 0 when a duration or until-the-next says how long instead
     * @param duration how long from {@code at}; null unless it says how long
     */
    record Length(int days, @Nullable LocalTime at, @Nullable Duration duration, boolean untilNext) {

        public Length {
            if (LocalTime.MIDNIGHT.equals(at)) {
                at = null;
            }
            if (untilNext) {
                days = 0;
                duration = null;
            } else if (duration != null && duration.isPositive()) {
                days = 0;
            } else {
                duration = null;
                days = Math.max(1, days);
            }
        }

        /** {@code days} whole days from midnight. */
        @Nonnull
        public static Length days(int days) {
            return new Length(days, null, null, false);
        }

        /** {@code days} whole days from {@code at} on the first (midnight when null). */
        @Nonnull
        public static Length days(int days, @Nullable LocalTime at) {
            return new Length(days, at, null, false);
        }

        /** {@code duration} from {@code at} (midnight when null). */
        @Nonnull
        public static Length timed(@Nullable LocalTime at, @Nonnull Duration duration) {
            return new Length(0, at, duration, false);
        }

        /** From {@code at} (midnight when null) until the next run starts. */
        @Nonnull
        public static Length untilNext(@Nullable LocalTime at) {
            return new Length(0, at, null, true);
        }
    }

    /**
     * A run each month the rule names: on the {@code day} of the month (a day a month lacks falls back to its
     * last), or on the {@code nth} {@code weekday} of it when a weekday is named (1 to 5, or {@link #LAST}; a fifth
     * a month lacks falls back to the fourth). A weekday rule keeps no day (0) and a day rule no {@code nth} (0).
     */
    record Monthly(int day, @Nullable DayOfWeek weekday, int nth, int every, @Nonnull Set<Month> months,
                   @Nonnull Length length) implements Recurrence {

        public Monthly {
            if (weekday != null) {
                day = 0;
            } else {
                nth = 0;
            }
            every = Math.max(1, every);
            months = named(months);
            Objects.requireNonNull(length, "a run lasts some time");
        }

        /** On the {@code day} of each month it names. */
        @Nonnull
        public static Monthly onDay(int day, int every, @Nullable Set<Month> months, @Nonnull Length length) {
            return new Monthly(day, null, 0, every, months, length);
        }

        /** On the {@code nth} {@code weekday} of each month it names. */
        @Nonnull
        public static Monthly onWeekday(int nth, @Nonnull DayOfWeek weekday, int every, @Nullable Set<Month> months,
                @Nonnull Length length) {
            return new Monthly(0, weekday, nth, every, months, length);
        }
    }

    /** A run on {@code weekday} each week the rule names, in the months it names. */
    record Weekly(@Nonnull DayOfWeek weekday, int every, @Nonnull Set<Month> months, @Nonnull Length length)
            implements Recurrence {

        public Weekly {
            Objects.requireNonNull(weekday, "a weekly run falls on a weekday");
            every = Math.max(1, every);
            months = named(months);
            Objects.requireNonNull(length, "a run lasts some time");
        }
    }
}
