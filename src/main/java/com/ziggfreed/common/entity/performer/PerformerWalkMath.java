package com.ziggfreed.common.entity.performer;

/**
 * Pure arrival / stuck decision cores for a performer walk - engine-free, unit-testable without a
 * live server. The {@link NpcRolePerformer} walk (whose motion the engine's own A* drives, so it
 * cannot key arrival off a travelled arc-length the way the bare-{@code Holder} walk does) measures
 * distance to the target and stall time; these are the tuned predicates it evaluates, kept here so
 * they are shared and tested rather than re-derived inline. Defaults follow the Hycompanion tuning
 * cited in the performer seam design.
 */
public final class PerformerWalkMath {

    /** Distance (blocks) within which a directed walk counts as arrived. */
    public static final double ARRIVE_RADIUS = 1.5;

    /** Lenient distance (blocks) accepted as "close enough" once the walk has stalled. */
    public static final double NEAR_MISS_RADIUS = 5.0;

    /** Movement (blocks) below which the walk is considered to have made no progress this window. */
    public static final double STUCK_EPS = 0.2;

    /** How long (ms) of no-progress before a stalled walk gives up. */
    public static final long STUCK_WINDOW_MS = 3_000L;

    private PerformerWalkMath() {
    }

    /** Pure: horizontal (XZ) distance between two points (Y ignored, matching a floor-plane walk). */
    public static double horizontalDistance(double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Pure: whether {@code dist} is within {@code arriveRadius} of the target. */
    public static boolean arrived(double dist, double arriveRadius) {
        return dist <= arriveRadius;
    }

    /** Pure: whether {@code dist} is within the lenient {@code nearMissRadius}. */
    public static boolean nearMiss(double dist, double nearMissRadius) {
        return dist <= nearMissRadius;
    }

    /**
     * Pure: whether a walk is STUCK - it has moved less than {@code stuckEps} over the accumulated
     * stall window AND that window has elapsed ({@code stalledMs >= stuckWindowMs}). A walk still
     * making progress (moved &gt;= eps) resets its window and is never stuck.
     */
    public static boolean stuck(double movedSinceLast, double stuckEps, long stalledMs, long stuckWindowMs) {
        return stalledMs >= stuckWindowMs && movedSinceLast < stuckEps;
    }
}
