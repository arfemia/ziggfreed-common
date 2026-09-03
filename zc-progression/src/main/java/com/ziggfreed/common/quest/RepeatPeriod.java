package com.ziggfreed.common.quest;

import javax.annotation.Nonnull;

import com.ziggfreed.common.quest.Quest.Repeat.Reset;
import com.ziggfreed.common.util.PeriodMath;

/**
 * The calendar arithmetic behind {@link Quest.Repeat.Reset}: which window an instant falls in, when
 * that window started, and when the next one begins.
 *
 * <p>What a {@code Reset} MEANS in window terms lives here - how long its window lasts and how far
 * its boundary is shifted - while the arithmetic over those two numbers is
 * {@link PeriodMath} in zc-core, so every recurring window in the library indexes the same way. A
 * window is any length (a day, a week, eight hours, a fortnight); only a window that is a whole
 * number of weeks has a weekday to start on.
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
    public static final long DAY_MS = PeriodMath.DAY_MS;

    /** Milliseconds in one week. */
    public static final long WEEK_MS = PeriodMath.WEEK_MS;

    private RepeatPeriod() {
    }

    /** How long one window lasts, in milliseconds: whatever length the reset was authored with. */
    public static long lengthMs(@Nonnull Reset reset) {
        return reset.periodMs();
    }

    /**
     * How far the window boundary is shifted from the raw epoch grid: the weekday start (for a
     * window that is a whole number of weeks, because any other length has no weekday to start on)
     * plus the authored minutes.
     */
    public static long anchorOffsetMs(@Nonnull Reset reset) {
        long weekdayShift = reset.weekAligned()
                ? PeriodMath.weekdayAnchorMs(reset.weekStart())
                : 0L;
        return weekdayShift - reset.atMinutes() * PeriodMath.MINUTE_MS;
    }

    /** Which window {@code nowMs} falls in. Monotonic, including for instants before the epoch. */
    public static long periodIndex(@Nonnull Reset reset, long nowMs) {
        return PeriodMath.periodIndex(lengthMs(reset), anchorOffsetMs(reset), nowMs);
    }

    /** When the window containing {@code nowMs} started, in epoch milliseconds. */
    public static long periodStartMs(@Nonnull Reset reset, long nowMs) {
        return PeriodMath.periodStartMs(lengthMs(reset), anchorOffsetMs(reset), nowMs);
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
        return PeriodMath.nextBoundaryMs(lengthMs(reset), anchorOffsetMs(reset), nowMs);
    }

    /** Do these two instants fall in the same window? */
    public static boolean samePeriod(@Nonnull Reset reset, long aMs, long bMs) {
        return PeriodMath.samePeriod(lengthMs(reset), anchorOffsetMs(reset), aMs, bMs);
    }
}
