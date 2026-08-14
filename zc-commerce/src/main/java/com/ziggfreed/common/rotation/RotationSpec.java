package com.ziggfreed.common.rotation;

import java.time.DayOfWeek;

import javax.annotation.Nonnull;

import com.ziggfreed.common.util.PeriodMath;

/**
 * When a rotating pool rolls over, and how its periods are numbered.
 *
 * <p>The RUNTIME cadence, not the authored shape: two numbers, a period LENGTH and the offset that
 * shifts its boundaries. What an author writes ({@code Period: "Daily"}, {@code Every: {Hours: 2}},
 * an offset, a weekday) is the authoring layer's group, folded into one of these. Reducing it to
 * two numbers here is what makes a whole rotation's life exercisable by handing it numbers.
 *
 * <p><b>Pure wall-clock arithmetic and zero persisted state.</b> The period index of any instant is
 * a stable integer, so a pool's active set can be a deterministic function of
 * {@code (poolId, period, seed)}: every player sees the same rotation, a restart changes nothing,
 * and there is no schedule to keep anywhere. The engine holds NO clock - every entry point takes
 * {@code nowMs}.
 */
public final class RotationSpec {

    private final long periodLengthMs;
    private final long anchorOffsetMs;

    private RotationSpec(long periodLengthMs, long anchorOffsetMs) {
        this.periodLengthMs = Math.max(1L, periodLengthMs);
        this.anchorOffsetMs = anchorOffsetMs;
    }

    /**
     * A cadence of {@code periodLengthMs}, with boundaries shifted by {@code anchorOffsetMs}. A
     * POSITIVE offset pulls every boundary earlier, which is how a weekday start is expressed; the
     * minute-shaped knob an author writes goes in negated, and {@link #withOffsetMinutes} does that.
     */
    @Nonnull
    public static RotationSpec of(long periodLengthMs, long anchorOffsetMs) {
        return new RotationSpec(periodLengthMs, anchorOffsetMs);
    }

    /** Turns over at midnight UTC. */
    @Nonnull
    public static RotationSpec daily() {
        return new RotationSpec(PeriodMath.DAY_MS, 0L);
    }

    /** Turns over at the start of Monday. */
    @Nonnull
    public static RotationSpec weekly() {
        return weeklyFrom(DayOfWeek.MONDAY);
    }

    /** Turns over at the start of {@code weekStart}. */
    @Nonnull
    public static RotationSpec weeklyFrom(@Nonnull DayOfWeek weekStart) {
        return new RotationSpec(PeriodMath.WEEK_MS, PeriodMath.weekdayAnchorMs(weekStart));
    }

    /** Turns over every {@code spanMs}, on its own rhythm rather than the calendar's. */
    @Nonnull
    public static RotationSpec every(long spanMs) {
        return new RotationSpec(spanMs, 0L);
    }

    /**
     * This cadence with every boundary moved {@code minutes} LATER, for a server whose day should
     * start at something other than midnight UTC.
     */
    @Nonnull
    public RotationSpec withOffsetMinutes(int minutes) {
        return new RotationSpec(periodLengthMs, anchorOffsetMs - minutes * PeriodMath.MINUTE_MS);
    }

    /** How long one rotation period lasts, in milliseconds. Never zero. */
    public long periodLengthMs() {
        return periodLengthMs;
    }

    /** How far this cadence's boundaries are shifted from the raw epoch grid. */
    public long anchorOffsetMs() {
        return anchorOffsetMs;
    }

    /** Which period {@code nowMs} falls in. The number a draw is seeded with. */
    public long periodIndex(long nowMs) {
        return PeriodMath.periodIndex(periodLengthMs, anchorOffsetMs, nowMs);
    }

    /** When the period containing {@code nowMs} began. */
    public long periodStartMs(long nowMs) {
        return PeriodMath.periodStartMs(periodLengthMs, anchorOffsetMs, nowMs);
    }

    /** How long until the next rotation. Always positive, a whole period on a boundary. */
    public long millisUntilNext(long nowMs) {
        return PeriodMath.millisUntilNext(periodLengthMs, anchorOffsetMs, nowMs);
    }

    /** When the next rotation happens, which a caller can hand a player as "back at". */
    public long nextRotationMs(long nowMs) {
        return PeriodMath.nextBoundaryMs(periodLengthMs, anchorOffsetMs, nowMs);
    }

    /**
     * Do these two instants fall in the same rotation period? The test behind "this was already
     * claimed this period", so a claimed slot stays claimed until the pool genuinely turns over.
     */
    public boolean samePeriod(long aMs, long bMs) {
        return PeriodMath.samePeriod(periodLengthMs, anchorOffsetMs, aMs, bMs);
    }

    @Override
    public String toString() {
        return "RotationSpec[" + periodLengthMs + "ms"
                + (anchorOffsetMs != 0L ? ", anchor " + anchorOffsetMs + "ms" : "") + "]";
    }
}
