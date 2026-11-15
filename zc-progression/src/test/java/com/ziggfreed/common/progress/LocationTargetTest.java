package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** REACH_LOCATION target parsing + containment + world scoping. */
public class LocationTargetTest {

    @Test
    void parse_coordsWithRadius() {
        LocationTarget t = LocationTarget.parse("120, 64, -200, 10");
        assertEquals(null, t.world());
        assertEquals(120.0, t.x());
        assertEquals(64.0, t.y());
        assertEquals(-200.0, t.z());
        assertEquals(10.0, t.radius());
    }

    @Test
    void parse_radiusDefaultsWhenOmitted() {
        LocationTarget t = LocationTarget.parse("0,70,0");
        assertEquals(LocationTarget.DEFAULT_RADIUS, t.radius());
    }

    @Test
    void parse_worldPrefix() {
        LocationTarget t = LocationTarget.parse("default:10,64,10,3");
        assertEquals("default", t.world());
        assertTrue(t.excludesWorld("arena"));
        assertFalse(t.excludesWorld("Default"));
        assertFalse(LocationTarget.parse("10,64,10").excludesWorld("anything"));
    }

    @Test
    void parse_rejectsGarbage() {
        assertNull(LocationTarget.parse(null));
        assertNull(LocationTarget.parse(""));
        assertNull(LocationTarget.parse("spawn"));
        assertNull(LocationTarget.parse("1,2"));
        assertNull(LocationTarget.parse("1,2,3,4,5"));
        assertNull(LocationTarget.parse("a,b,c"));
        assertNull(LocationTarget.parse("1,2,3,-5"), "non-positive radius rejected");
        assertNull(LocationTarget.parse("world:"), "world prefix with no coords");
    }

    @Test
    void contains_isSpherical() {
        LocationTarget t = LocationTarget.parse("0,64,0,5");
        assertTrue(t.contains(0, 64, 0));
        assertTrue(t.contains(3, 64, 4));
        assertFalse(t.contains(3, 68, 4), "3D distance counts the Y axis");
        assertFalse(t.contains(6, 64, 0));
    }
}
