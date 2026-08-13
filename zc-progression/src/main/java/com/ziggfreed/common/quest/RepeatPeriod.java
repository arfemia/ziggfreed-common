package com.ziggfreed.common.quest;

import java.time.DayOfWeek;

import javax.annotation.Nonnull;

import com.ziggfreed.common.quest.Quest.Repeat.Reset;

/**
 * The calendar arithmetic behind {@link Quest.Repeat.Reset}: which window an instant falls in, when
 * that window started, and when the next one begins.
 *
 * <p>Pure and static - no store, no clock, no engine type - so every boundary is exercisable by
 * handing it two numbers.
 *
 * <p><b>UTC server clock, no timezone.</b> A window is indexed off the epoch with
 * {@link Math#floorDiv}, which keeps the indexing monotonic for instants before 1970 as well as
 * after. {@link Reset#atMinutes()} is the offset knob for a server whose day should start at
 * something other than midnight UTC; a real timezone would have to be persisted per quest for an
 * already-stamped completion to keep meaning the same thing after an owner changed it.
 */
public final class RepeatPeriod {

    /** Milliseconds in one day. */
    public static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** Milliseconds in one week. */
    public static final long WEEK_MS = 7L * DAY_MS;

    /** Milliseconds in one minute, for the {@code atMinutes} shift. */
    private static final long MINUTE_MS = 60L * 1000L;

    /**
     * The epoch fell on a Thursday, so a weekly window anchored to a Thursday needs no shift and
     * every other weekday start is measured from it.
     */
    private static final DayOfWeek EPOCH_DAY = DayOfWeek.THURSDAY;

    private RepeatPeriod() {
    }

    /** How long one window lasts, in milliseconds. */
    public static long lengthMs(@Nonnull Reset reset) {
        return reset.period() == Reset.Period.WEEKLY ? WEEK_MS : DAY_MS;
    }

    /**
     * How far the window boundary is shifted from the raw epoch grid: the weekday start (weekly
     * only, because a daily window has no weekday to start on) plus the authored minutes.
     */
    public static long anchorOffsetMs(@Nonnull Reset reset) {
        long weekdayShift = 0L;
        if (reset.period() == Reset.Period.WEEKLY) {
            int delta = (EPOCH_DAY.getValue() - reset.weekStart().getValue() + 7) % 7;
            weekdayShift = delta * DAY_MS;
        }
        return weekdayShift - reset.atMinutes() * MINUTE_MS;
    }

    /** Which window {@code nowMs} falls in. Monotonic, including for instants before the epoch. */
    public static long periodIndex(@Nonnull Reset reset, long nowMs) {
        return Math.floorDiv(nowMs + anchorOffsetMs(reset), lengthMs(reset));
    }

    /** When the window containing {@code nowMs} started, in epoch milliseconds. */
    public static long periodStartMs(@Nonnull Reset reset, long nowMs) {
        return periodIndex(reset, nowMs) * lengthMs(reset) - anchorOffsetMs(reset);
    }

    /**
     * When the window containing {@code nowMs} ends, which is the instant the next one begins.
     * Always strictly greater than {@code nowMs}, including when {@code nowMs} sits exactly on a
     * boundary, so a caller can hand it straight to a player as "back at".
     *
     * <p>A clock near the far end of the long range SATURATES at {@link Long#MAX_VALUE} rather than
     * wrapping into a negative number, because a wrapped boundary would read as "offerable now".
     */
    public static long nextBoundaryMs(@Nonnull Reset reset, long nowMs) {
        long length = lengthMs(reset);
        long start = periodStartMs(reset, nowMs);
        try {
            return Math.addExact(start, length);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Do these two instants fall in the same window? */
    public static boolean samePeriod(@Nonnull Reset reset, long aMs, long bMs) {
        return periodIndex(reset, aMs) == periodIndex(reset, bMs);
    }
}
