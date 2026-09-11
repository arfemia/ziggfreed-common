package com.ziggfreed.common.entity.overhead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Where a marker sits over its host, as arithmetic. */
class OverheadAnchorTest {

    @Test
    void theAnchorIsTheTallerReadingOrAHumanHeightForNone() {
        assertEquals(1.6, OverheadAnchor.anchorHeight(1.6, 1.2));
        assertEquals(2.4, OverheadAnchor.anchorHeight(1.6, 2.4));
        assertEquals(OverheadAnchor.FALLBACK_HEIGHT, OverheadAnchor.anchorHeight(0, 0));
        assertEquals(OverheadAnchor.FALLBACK_HEIGHT, OverheadAnchor.anchorHeight(-1, 0));
    }

    @Test
    void theTargetIsTheHostPlusTheAnchorPlusTheLooksOffset() {
        Vector3d target = OverheadAnchor.target(new Vector3d(10, 64, -5), 1.8, 0.1, 0.45, -0.2);
        assertEquals(10.1, target.x, 1e-9);
        assertEquals(66.25, target.y, 1e-9);
        assertEquals(-5.2, target.z, 1e-9);
    }

    @Test
    void aMoveInsideTheEpsilonIsNotAMove() {
        Vector3d at = new Vector3d(1, 1, 1);
        assertFalse(OverheadAnchor.moved(at, new Vector3d(1.01, 1, 1), 0.02));
        assertTrue(OverheadAnchor.moved(at, new Vector3d(1.03, 1, 1), 0.02));
        assertTrue(OverheadAnchor.moved(at, new Vector3d(1, 1.5, 1), 0.02));
    }
}
