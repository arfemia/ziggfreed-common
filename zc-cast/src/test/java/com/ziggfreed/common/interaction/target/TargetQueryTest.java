package com.ziggfreed.common.interaction.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

class TargetQueryTest {

    @Test
    void defaultsMatchDocumentedValues() {
        TargetQuery q = TargetQuery.builder(new Vector3d(1, 2, 3)).build();

        assertEquals(new Vector3d(1, 2, 3), q.origin());
        assertNull(q.direction());
        assertNull(q.owner());
        assertEquals(0.0, q.minDistance(), 1e-9);
        assertEquals(0.0, q.maxDistance(), 1e-9);
        assertNull(q.coneAngleDegrees());
        assertNull(q.verticalExtent());
        assertEquals(0.0, q.inflateRadius(), 1e-9);
        assertFalse(q.requireLineOfSight());
        assertEquals(0.3, q.lineOfSightStep(), 1e-9);
        assertEquals(0, q.maxTargets());
        assertEquals(0, q.excludedIndices().length);
        assertNull(q.filter());
        assertTrue(q.nearestFirst());
    }

    @Test
    void negativeDistancesAndInflateClampToZero() {
        TargetQuery q = TargetQuery.builder(new Vector3d())
                .minDistance(-5.0)
                .maxDistance(-1.0)
                .inflateRadius(-2.0)
                .build();

        assertEquals(0.0, q.minDistance(), 1e-9);
        assertEquals(0.0, q.maxDistance(), 1e-9);
        assertEquals(0.0, q.inflateRadius(), 1e-9);
    }

    @Test
    void nonPositiveLineOfSightStepFallsBackToDefault() {
        TargetQuery zero = TargetQuery.builder(new Vector3d()).lineOfSightStep(0.0).build();
        TargetQuery negative = TargetQuery.builder(new Vector3d()).lineOfSightStep(-1.0).build();

        assertEquals(0.3, zero.lineOfSightStep(), 1e-9);
        assertEquals(0.3, negative.lineOfSightStep(), 1e-9);
    }

    @Test
    void zeroLengthDirectionNormalizesToNull() {
        TargetQuery q = TargetQuery.builder(new Vector3d()).direction(new Vector3d(0, 0, 0)).build();
        assertNull(q.direction());
    }

    @Test
    void nonUnitDirectionIsNormalized() {
        TargetQuery q = TargetQuery.builder(new Vector3d()).direction(new Vector3d(3, 0, 0)).build();
        Vector3d dir = q.direction();
        assertEquals(1.0, dir.x, 1e-9);
        assertEquals(0.0, dir.y, 1e-9);
        assertEquals(0.0, dir.z, 1e-9);
        assertEquals(1.0, dir.length(), 1e-9);
    }

    @Test
    void coneAngleDegreesClampsToZeroOneEighty() {
        TargetQuery low = TargetQuery.builder(new Vector3d()).coneAngleDegrees(-30.0).build();
        TargetQuery high = TargetQuery.builder(new Vector3d()).coneAngleDegrees(360.0).build();
        TargetQuery mid = TargetQuery.builder(new Vector3d()).coneAngleDegrees(45.0).build();

        assertEquals(0.0, low.coneAngleDegrees(), 1e-9);
        assertEquals(180.0, high.coneAngleDegrees(), 1e-9);
        assertEquals(45.0, mid.coneAngleDegrees(), 1e-9);
    }

    @Test
    void excludeDedupesDuplicateIndices() {
        TargetQuery q = TargetQuery.builder(new Vector3d())
                .exclude(7L)
                .exclude(7L)
                .exclude(9L)
                .build();

        long[] excluded = q.excludedIndices();
        assertEquals(2, excluded.length);
        assertTrue(contains(excluded, 7L));
        assertTrue(contains(excluded, 9L));
    }

    @Test
    void excludeWithNullRefIsNoOp() {
        TargetQuery q = TargetQuery.builder(new Vector3d()).exclude((Ref<EntityStore>) null).build();
        assertEquals(0, q.excludedIndices().length);
    }

    @Test
    void originDirectionAndExcludedIndicesAreDefensiveCopies() {
        Vector3d origin = new Vector3d(1, 1, 1);
        Vector3d direction = new Vector3d(1, 0, 0);
        TargetQuery q = TargetQuery.builder(origin).direction(direction).exclude(4L).build();

        // Mutating the builder's inputs after build() never reaches the query.
        origin.set(9, 9, 9);
        direction.set(0, 1, 0);
        assertEquals(new Vector3d(1, 1, 1), q.origin());
        assertEquals(1.0, q.direction().x, 1e-9);

        // Mutating a returned value never reaches the next read.
        Vector3d o1 = q.origin();
        o1.set(5, 5, 5);
        assertEquals(new Vector3d(1, 1, 1), q.origin());

        Vector3d d1 = q.direction();
        d1.set(0, 0, 1);
        assertEquals(1.0, q.direction().x, 1e-9);

        long[] indices = q.excludedIndices();
        indices[0] = 999;
        assertEquals(4L, q.excludedIndices()[0]);
    }

    private static boolean contains(long[] arr, long v) {
        for (long e : arr) {
            if (e == v) return true;
        }
        return false;
    }
}
