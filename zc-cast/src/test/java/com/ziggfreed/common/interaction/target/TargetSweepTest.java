package com.ziggfreed.common.interaction.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Guard-only characterization tests. {@link TargetSweep} cannot run a LIVE sweep in a unit
 * JVM ({@code Selector}/{@code RaycastTargeting} need a real, engine-constructed
 * {@code Store}); every case here exercises the argument guards that run BEFORE any engine
 * touch, per the class javadoc - never a live sweep.
 */
class TargetSweepTest {

    @Test
    void volumeWithNullStoreAndNullQueryIsEmpty() {
        assertEquals(List.of(), TargetSweep.volume(null, null));
    }

    @Test
    void volumeWithNullStoreAndRealQueryIsEmpty() {
        TargetQuery query = TargetQuery.builder(new Vector3d()).maxDistance(10.0).build();
        assertEquals(List.of(), TargetSweep.volume(null, query));
    }

    @Test
    void volumeWithZeroMaxDistanceIsEmpty() {
        TargetQuery query = TargetQuery.builder(new Vector3d()).maxDistance(0.0).build();
        assertEquals(List.of(), TargetSweep.volume(null, query));
    }

    @Test
    void rayWithNullQueryIsEmpty() {
        assertEquals(List.of(), TargetSweep.ray(null, null));
    }

    @Test
    void rayWithNullOwnerIsEmpty() {
        TargetQuery query = TargetQuery.builder(new Vector3d())
                .maxDistance(10.0)
                .direction(new Vector3d(1, 0, 0))
                .build();
        assertNull(query.owner());
        assertEquals(List.of(), TargetSweep.ray(null, query));
    }

    @Test
    void rayWithNullDirectionIsEmpty() {
        TargetQuery query = TargetQuery.builder(new Vector3d()).maxDistance(10.0).build();
        assertNull(query.direction());
        assertEquals(List.of(), TargetSweep.ray(null, query));
    }

    @Test
    void everyReturnIsImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> TargetSweep.volume(null, null).add(null));
        assertThrows(UnsupportedOperationException.class, () -> TargetSweep.ray(null, null).add(null));
    }

    @Test
    void nearestReducersReturnNullOnEmptySweep() {
        assertNull(TargetSweep.nearestInVolume(null, null));
        assertNull(TargetSweep.nearestOnRay(null, null));
    }
}
