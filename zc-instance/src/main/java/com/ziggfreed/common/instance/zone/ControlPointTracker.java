package com.ziggfreed.common.instance.zone;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A NON-LATCHING per-team capture / contest / control state machine for a single control point - the
 * Domination engine. A point starts neutral, a dominant team captures it over a configured number of
 * seconds, and (unlike {@link ZoneHoldTimer}) it can FLIP back and forth between teams indefinitely:
 * a different team standing on a point another team owns accrues capture time and takes it over.
 *
 * <p><b>Why NOT {@link ZoneHoldTimer}.</b> {@code ZoneHoldTimer} models a co-op "hold this zone for X
 * seconds" objective and LATCHES {@code complete()} forever on the first success - perfect for a one-shot
 * extraction pad, useless for a point that changes hands. A Domination point is adversarial and repeatable:
 * it has a per-team controller that can be neutralized/recaptured, it can be contested by multiple teams
 * at once, and progress accrues toward the CURRENT capturer, never toward a single latched winner. This is
 * its own state machine, not a tweak of the hold timer.
 *
 * <p><b>Pure + deterministic.</b> No engine calls and no wall-clock reads: the caller feeds the live
 * per-team occupant counts and its own {@code nowMillis} each tick (mirroring {@code EncounterDirector} and
 * {@code ZoneHoldTimer}), so this never reaches into a {@code Store}/{@code World} and never calls
 * {@link System#currentTimeMillis()} itself. The consumer owns WHO counts as an occupant of which team and
 * WHERE the point is; this owns only the capture/contest/control state machine + the HUD-facing queries.
 *
 * <p><b>Not thread-safe.</b> Drive it from one thread (the instance/round tick), like the per-round state
 * that owns it.
 */
public final class ControlPointTracker {

    private final long captureMillis;

    /** The team that currently controls the point; {@code null} = neutral. */
    @Nullable
    private String controllingTeam;
    /** The team currently accruing capture progress toward a flip/initial capture; {@code null} = none. */
    @Nullable
    private String capturingTeam;
    /** Accrued capture time (millis) the {@link #capturingTeam} has banked toward {@link #captureMillis}. */
    private long capturedMillis;
    /** Whether the point is contested this tick (multiple teams present, no dominant team); freezes progress. */
    private boolean contested;
    /** The caller's-clock time of the previous {@link #update}; {@code < 0} until the first update seeds it. */
    private long lastMillis = -1L;

    /**
     * @param captureSeconds how long a dominant team must hold an un-owned point to capture/flip it;
     *                       clamped to {@code >= 0} ({@code 0} captures the instant a team is dominant alone).
     */
    public ControlPointTracker(double captureSeconds) {
        this.captureMillis = Math.round(Math.max(0.0, captureSeconds) * 1000.0);
    }

    /**
     * Advance the capture state one tick from the current per-team occupancy.
     *
     * <p>Resolution order:
     * <ul>
     *   <li>Compute the DOMINANT team for {@code rule} (ANY_PRESENCE: the only team with {@code > 0}
     *       occupants; MAJORITY: the strict-max occupant team, none on a tie for the lead).</li>
     *   <li>Empty point (no occupants at all): not contested, progress and controller unchanged.</li>
     *   <li>No dominant team but {@code > 1} team present: CONTESTED - progress is frozen (NOT reset),
     *       controller unchanged.</li>
     *   <li>Dominant team == current controller: it already owns the point, so no progress change (any
     *       in-flight capture by that team is cleared - it has reclaimed its own point).</li>
     *   <li>Dominant team != controller: accrue the elapsed delta toward capturing the point; on reaching
     *       {@code captureSeconds}, FLIP {@link #controllingTeam} to that team and zero progress.</li>
     * </ul>
     *
     * @param occupantsByTeam live occupant count keyed by teamId (a non-positive or absent count = no
     *                        presence; never mutated)
     * @param rule            how to pick the dominant team this tick
     * @param nowMillis       the caller's current time (same clock across calls); the first call seeds the
     *                        elapsed baseline and accrues nothing
     */
    public void update(@Nonnull Map<String, Integer> occupantsByTeam, @Nonnull ContestRule rule, long nowMillis) {
        long deltaMillis;
        if (lastMillis < 0L) {
            // First update seeds the baseline; no time has elapsed yet under our clock.
            deltaMillis = 0L;
        } else {
            deltaMillis = nowMillis - lastMillis;
            if (deltaMillis < 0L) {
                deltaMillis = 0L; // clock went backwards; treat as no elapsed time, never negative-accrue
            }
        }
        lastMillis = nowMillis;

        int teamsPresent = 0;
        for (Integer count : occupantsByTeam.values()) {
            if (count != null && count > 0) {
                teamsPresent++;
            }
        }

        // Empty point: nobody present. Not contested; progress + controller untouched.
        if (teamsPresent == 0) {
            contested = false;
            return;
        }

        String dominant = dominantTeam(occupantsByTeam, rule);

        // No dominant team while more than one team is present: contested. Freeze progress, keep controller.
        if (dominant == null) {
            contested = teamsPresent > 1;
            return;
        }

        contested = false;

        // Dominant team already controls the point: it holds, no progress change. Clear any stale in-flight
        // capture by that same team (it is back in control of what it was capturing).
        if (dominant.equals(controllingTeam)) {
            capturingTeam = null;
            capturedMillis = 0L;
            return;
        }

        // A new dominant team is capturing toward a flip / initial capture.
        if (!dominant.equals(capturingTeam)) {
            capturingTeam = dominant;
            capturedMillis = 0L;
        }
        capturedMillis += deltaMillis;
        if (capturedMillis >= captureMillis) {
            controllingTeam = dominant;
            capturingTeam = null;
            capturedMillis = 0L;
        }
    }

    /**
     * The dominant team for {@code rule}, or {@code null} if none (no presence, or contested). Assumes at
     * least one team is present (callers gate on that first).
     */
    @Nullable
    private static String dominantTeam(@Nonnull Map<String, Integer> occupantsByTeam, @Nonnull ContestRule rule) {
        switch (rule) {
            case ANY_PRESENCE: {
                String only = null;
                for (Map.Entry<String, Integer> e : occupantsByTeam.entrySet()) {
                    Integer count = e.getValue();
                    if (count != null && count > 0) {
                        if (only != null) {
                            return null; // a second team present -> contested
                        }
                        only = e.getKey();
                    }
                }
                return only;
            }
            case MAJORITY: {
                String leader = null;
                int best = 0;
                boolean tie = false;
                for (Map.Entry<String, Integer> e : occupantsByTeam.entrySet()) {
                    Integer count = e.getValue();
                    if (count == null || count <= 0) {
                        continue;
                    }
                    if (count > best) {
                        best = count;
                        leader = e.getKey();
                        tie = false;
                    } else if (count == best) {
                        tie = true; // matched the current lead -> no strict max (so far)
                    }
                }
                return tie ? null : leader;
            }
            default:
                return null;
        }
    }

    /** The team that currently controls the point, or {@code null} if neutral. */
    @Nullable
    public String controllingTeam() {
        return controllingTeam;
    }

    /** The team currently accruing capture progress, or {@code null} if none. */
    @Nullable
    public String capturingTeam() {
        return capturingTeam;
    }

    /**
     * Capture progress {@code 0..1} toward a flip (or toward the initial capture from neutral). {@code 0.0}
     * when no team is capturing; {@code 1.0} when {@code captureSeconds} is zero and a team is capturing.
     */
    public double captureProgress() {
        if (capturingTeam == null) {
            return 0.0;
        }
        if (captureMillis <= 0L) {
            return 1.0;
        }
        double p = (double) capturedMillis / captureMillis;
        return p < 0.0 ? 0.0 : Math.min(p, 1.0);
    }

    /** Whether the point is contested this tick (multiple teams present with no dominant team). */
    public boolean isContested() {
        return contested;
    }

    /** Reset to neutral with no progress: clears controller, capturer, accrued time, and contest state. */
    public void reset() {
        controllingTeam = null;
        capturingTeam = null;
        capturedMillis = 0L;
        contested = false;
        lastMillis = -1L;
    }
}
