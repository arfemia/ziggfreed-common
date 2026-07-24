package com.ziggfreed.common.entity.performer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The PURE walk arrival / stuck cores ({@link PerformerWalkMath}) the {@link NpcRolePerformer} walk
 * evaluates - no live server. The engine-driven distance READ is untested (needs a live transform),
 * matching the package's split.
 */
class PerformerWalkMathTest {

    private static final double EPS = 1e-9;

    @Test
    void horizontalDistance_ignoresY() {
        assertEquals(5.0, PerformerWalkMath.horizontalDistance(0, 0, 3, 4), EPS, "3-4-5 in XZ");
        // Y is not a parameter: two calls with the same XZ are equal regardless of vertical gap.
        assertEquals(PerformerWalkMath.horizontalDistance(0, 0, 3, 4),
                PerformerWalkMath.horizontalDistance(0, 0, 3, 4), EPS);
    }

    @Test
    void arrived_withinRadius() {
        assertTrue(PerformerWalkMath.arrived(1.5, PerformerWalkMath.ARRIVE_RADIUS));
        assertTrue(PerformerWalkMath.arrived(0.0, PerformerWalkMath.ARRIVE_RADIUS));
        assertFalse(PerformerWalkMath.arrived(1.51, PerformerWalkMath.ARRIVE_RADIUS));
    }

    @Test
    void nearMiss_withinLenientRadius() {
        assertTrue(PerformerWalkMath.nearMiss(5.0, PerformerWalkMath.NEAR_MISS_RADIUS));
        assertFalse(PerformerWalkMath.nearMiss(5.01, PerformerWalkMath.NEAR_MISS_RADIUS));
    }

    @Test
    void stuck_onlyWhenStalledLongEnoughAndBarelyMoving() {
        long window = PerformerWalkMath.STUCK_WINDOW_MS;
        double eps = PerformerWalkMath.STUCK_EPS;
        // Barely moving AND window elapsed -> stuck.
        assertTrue(PerformerWalkMath.stuck(0.1, eps, window, window));
        assertTrue(PerformerWalkMath.stuck(0.1, eps, window + 500, window));
        // Barely moving but window NOT elapsed -> not yet stuck.
        assertFalse(PerformerWalkMath.stuck(0.1, eps, window - 1, window));
        // Making real progress -> never stuck, even past the window.
        assertFalse(PerformerWalkMath.stuck(0.5, eps, window * 2, window));
    }
}
