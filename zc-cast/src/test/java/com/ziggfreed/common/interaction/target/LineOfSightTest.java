package com.ziggfreed.common.interaction.target;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * A null {@code World} is the only case reachable without an engine bootstrap - it is also
 * the documented CLEAR convention (see {@link LineOfSight} javadoc), so every case here stays
 * inside that convention.
 */
class LineOfSightTest {

    @Test
    void nullWorldIsClear() {
        assertTrue(LineOfSight.clear(null, new Vector3d(0, 0, 0), new Vector3d(10, 0, 0)));
    }

    @Test
    void nullFromOrToIsClear() {
        assertTrue(LineOfSight.clear(null, null, new Vector3d(1, 1, 1)));
        assertTrue(LineOfSight.clear(null, new Vector3d(1, 1, 1), null));
        assertTrue(LineOfSight.clear(null, null, null));
    }

    @Test
    void sameFromAndToIsClear() {
        Vector3d p = new Vector3d(3, 3, 3);
        assertTrue(LineOfSight.clear(null, p, new Vector3d(3, 3, 3)));
        assertTrue(LineOfSight.clear(null, p, p));
    }

    @Test
    void nonPositiveStepFallsBackAndStillClearsWithNullWorld() {
        assertTrue(LineOfSight.clear(null, new Vector3d(0, 0, 0), new Vector3d(5, 0, 0), 0.0));
        assertTrue(LineOfSight.clear(null, new Vector3d(0, 0, 0), new Vector3d(5, 0, 0), -1.0));
    }
}
