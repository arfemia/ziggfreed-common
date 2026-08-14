package com.ziggfreed.common.util;

import java.time.DayOfWeek;

/**
 * Which repeating window an instant falls in, when that window started, and when the next one
 * begins. The ONE answer to "which period is this?" for every recurring window in the library.
 *
 * <p>A window is described by two plain numbers, so nothing here learns what the window is FOR: its
 * {@code lengthMs}, and an {@code anchorOffsetMs} shift added to the clock before the divide. An
 * offset of zero puts boundaries on the raw epoch grid (midnight UTC for a day-long window); a
 * positive offset moves each boundary EARLIER in wall-clock terms, which is how a weekly window
 * lands on a chosen weekday and how an authored start-time knob is expressed.
 *
 * <p><b>UTC server clock, no timezone.</b> Indexing goes through {@link Math#floorDiv}, so it stays
 * monotonic for instants before 1970 as well as after. A real timezone would have to be persisted
 * per window for an already-stamped instant to keep meaning the same thing after an owner changed
 * it, which is why the offset is the knob rather than a zone id.
 *
 * <p>Pure and static: no store, no clock, no engine type. Every boundary is exercisable by handing
 * it two numbers.
 */
public final class PeriodMath {

    /** Milliseconds in one second. */
    public static final long SECOND_MS = 1000L;

    /** Milliseconds in one minute. */
    public static final long MINUTE_MS = 60L * SECOND_MS;

    /** Milliseconds in one hour. */
    public static final long HOUR_MS = 60L * MINUTE_MS;

    /** Milliseconds in one day. */
    public static final long DAY_MS = 24L * HOUR_MS;

    /** Milliseconds in one week. */
    public static final long WEEK_MS = 7L * DAY_MS;

    /**
     * The epoch fell on a Thursday, so a weekly window anchored to a Thursday needs no shift and
     * every other weekday start is measured from it.
     */
    private static final DayOfWeek EPOCH_DAY = DayOfWeek.THURSDAY;

    private PeriodMath() {
    }

    /**
     * How far a weekly window's boundary is shifted from the raw epoch grid so it lands on
     * {@code weekStart}. Zero for Thursday, which is the weekday the epoch itself fell on.
     */
    public static long weekdayAnchorMs(DayOfWeek weekStart) {
        if (weekStart == null) {
            return 0L;
        }
        int delta = (EPOCH_DAY.getValue() - weekStart.getValue() + 7) % 7;
        return delta * DAY_MS;
    }

    /**
     * Which window {@code nowMs} falls in. Monotonic, including for instants before the epoch. A
     * non-positive {@code lengthMs} answers 0, since a window with no length contains everything.
     */
    public static long periodIndex(long lengthMs, long anchorOffsetMs, long nowMs) {
        if (lengthMs <= 0L) {
            return 0L;
        }
        return Math.floorDiv(nowMs + anchorOffsetMs, lengthMs);
    }

    /** When the window containing {@code nowMs} started, in epoch milliseconds. */
    public static long periodStartMs(long lengthMs, long anchorOffsetMs, long nowMs) {
        if (lengthMs <= 0L) {
            return nowMs;
        }
        return periodIndex(lengthMs, anchorOffsetMs, nowMs) * lengthMs - anchorOffsetMs;
    }

    /**
     * How long is left of the window containing {@code nowMs}. Always strictly positive: an instant
     * sitting exactly on a boundary has a WHOLE window ahead of it, which is what a countdown shown
     * to a player has to say.
     */
    public static long millisUntilNext(long lengthMs, long anchorOffsetMs, long nowMs) {
        if (lengthMs <= 0L) {
            return 0L;
        }
        return lengthMs - Math.floorMod(nowMs + anchorOffsetMs, lengthMs);
    }

    /**
     * When the window containing {@code nowMs} ends, which is the instant the next one begins.
     * Always strictly greater than {@code nowMs}, including on a boundary, so a caller can hand it
     * straight to a player as "back at".
     *
     * <p>A clock near the far end of the long range SATURATES at {@link Long#MAX_VALUE} rather than
     * wrapping into a negative number, because a wrapped boundary would read as "available now".
     */
    public static long nextBoundaryMs(long lengthMs, long anchorOffsetMs, long nowMs) {
        if (lengthMs <= 0L) {
            return nowMs;
        }
        try {
            return Math.addExact(periodStartMs(lengthMs, anchorOffsetMs, nowMs), lengthMs);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Do these two instants fall in the same window? */
    public static boolean samePeriod(long lengthMs, long anchorOffsetMs, long aMs, long bMs) {
        return periodIndex(lengthMs, anchorOffsetMs, aMs) == periodIndex(lengthMs, anchorOffsetMs, bMs);
    }
}
