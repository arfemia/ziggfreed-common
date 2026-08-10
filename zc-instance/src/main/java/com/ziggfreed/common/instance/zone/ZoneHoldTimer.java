package com.ziggfreed.common.instance.zone;

/**
 * A continuous co-op "hold this zone" timer: the required number of occupants must stay present in a
 * zone TOGETHER for a configured duration, and the hold RESETS the instant the group breaks (an occupant
 * leaves or drops below the required count). Generalized from Kweebec Nightmare's extraction platform into a
 * PURE, engine-free primitive any instance can reuse - an extraction pad, a capture point, a
 * king-of-the-hill objective, a ward-defense hold.
 *
 * <p><b>Pure + deterministic.</b> No engine calls and no wall-clock reads: the caller feeds the live
 * occupant/required counts and its own {@code nowMillis} each tick (mirroring {@code EncounterDirector}),
 * so this never reaches into a {@code Store}/{@code World} and never calls {@link System#currentTimeMillis()}
 * itself. The consumer owns WHO counts as an occupant, WHERE the zone is, and what happens on completion;
 * this owns only the continuous-hold-with-reset state machine + the HUD-facing progress queries.
 *
 * <p><b>Not thread-safe.</b> Drive it from one thread (the instance/round tick), like the per-round
 * {@code ChaseState} that owns it.
 */
public final class ZoneHoldTimer {

    private final long holdMillis;
    /** Whether a continuous hold is currently accruing. Distinct from {@code startMillis} so a hold that
     * begins at clock-zero is tracked correctly (no "0 == idle" sentinel collision). */
    private boolean active;
    /** The caller's-clock time the current hold began; valid only while {@link #active}. */
    private long startMillis;
    /** Latched once the hold has lasted the full duration (stays true so the caller resolves exactly once). */
    private boolean complete;

    /**
     * @param holdSeconds how long the required group must hold the zone continuously; clamped to {@code >= 0}
     *                    ({@code 0} completes the instant the group is all present).
     */
    public ZoneHoldTimer(double holdSeconds) {
        this.holdMillis = Math.round(Math.max(0.0, holdSeconds) * 1000.0);
    }

    /**
     * Advance the hold one tick from the current occupancy. The hold accrues while
     * {@code occupants >= required} (and {@code required > 0}) and RESETS to zero the instant that breaks.
     * Returns {@code true} once the continuous hold has lasted the configured duration, and stays
     * {@code true} thereafter, so the caller resolves the objective on the first {@code true}.
     *
     * @param occupants  how many required members are currently in the zone
     * @param required   how many must be present together ({@code <= 0} can never complete)
     * @param nowMillis  the caller's current time (same clock across calls)
     */
    public boolean update(int occupants, int required, long nowMillis) {
        if (complete) {
            return true;
        }
        boolean holding = required > 0 && occupants >= required;
        if (!holding) {
            active = false;
            return false;
        }
        if (!active) {
            active = true;
            startMillis = nowMillis;
        }
        if (nowMillis - startMillis >= holdMillis) {
            complete = true;
        }
        return complete;
    }

    /** Whether the group is currently assembled and the hold is accruing (not reset, not yet complete). */
    public boolean isHolding() {
        return active && !complete;
    }

    /** Whether the hold has finished (latched). */
    public boolean isComplete() {
        return complete;
    }

    /** The configured hold duration in whole seconds (rounded up). */
    public int holdSeconds() {
        return (int) Math.ceil(holdMillis / 1000.0);
    }

    /**
     * Whole seconds left in the current continuous hold at {@code nowMillis}: the full duration while
     * idle/reset (the hold has not started), {@code 0} once complete. Drives a HUD countdown.
     */
    public int remainingSeconds(long nowMillis) {
        if (complete) {
            return 0;
        }
        if (!active) {
            return (int) Math.ceil(holdMillis / 1000.0);
        }
        long left = holdMillis - (nowMillis - startMillis);
        return left <= 0L ? 0 : (int) Math.ceil(left / 1000.0);
    }

    /** Hold progress {@code 0..1} at {@code nowMillis} ({@code 1.0} once complete, {@code 0.0} while idle). */
    public double progress(long nowMillis) {
        if (complete || holdMillis <= 0L) {
            return 1.0;
        }
        if (!active) {
            return 0.0;
        }
        double p = (double) (nowMillis - startMillis) / holdMillis;
        return p < 0.0 ? 0.0 : Math.min(p, 1.0);
    }

    /** Reset the current hold (the group broke). Does NOT clear a latched {@link #isComplete()}. */
    public void reset() {
        active = false;
    }
}
