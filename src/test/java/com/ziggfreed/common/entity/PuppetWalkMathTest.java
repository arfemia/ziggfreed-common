package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.joml.Vector3d;

import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.ziggfreed.common.entity.PlayerPuppetService.WalkSample;

/**
 * Deterministic unit tests for {@link PlayerPuppetService}'s PURE locomotion cores (no live server):
 * waypoint arc-length lerp ({@link PlayerPuppetService#sampleWalk}), {@link PlayerPuppetService#pathLength},
 * the {@link PlayerPuppetService#isArrived} epsilon contract, and the yaw-from-heading mapping
 * ({@code sampleWalk}'s direction fed through the engine's own {@link PhysicsMath#headingFromDirection},
 * which is itself a pure server-jar function usable off a live server). The engine-touching
 * {@link PlayerPuppetService#walkTick}/{@link PlayerPuppetService#setWalking} writes have no unit
 * coverage (they need live component types), matching the puppet class's established test split.
 */
class PuppetWalkMathTest {

    private static final double EPS = 1e-9;

    // ==================== pathLength ====================

    @Test
    void pathLength_emptyOrSingle_isZero() {
        assertEquals(0.0, PlayerPuppetService.pathLength(new double[][] {}), EPS);
        assertEquals(0.0, PlayerPuppetService.pathLength(new double[][] {{1, 2, 3}}), EPS);
    }

    @Test
    void pathLength_straightSegment_isEuclidean() {
        assertEquals(4.0, PlayerPuppetService.pathLength(new double[][] {{0, 64, 0}, {4, 64, 0}}), EPS);
    }

    @Test
    void pathLength_multiSegment_sumsSegments() {
        double[][] pts = {{0, 64, 0}, {2, 64, 0}, {2, 64, 3}};
        assertEquals(5.0, PlayerPuppetService.pathLength(pts), EPS);
    }

    @Test
    void pathLength_vector3dOverload_matches() {
        List<Vector3d> pts = List.of(new Vector3d(0, 64, 0), new Vector3d(3, 64, 4));
        assertEquals(5.0, PlayerPuppetService.pathLength(pts), EPS, "3-4-5 diagonal is length 5");
        assertEquals(0.0, PlayerPuppetService.pathLength(List.of(new Vector3d(1, 1, 1))), EPS);
    }

    // ==================== sampleWalk (waypoint lerp) ====================

    @Test
    void sampleWalk_atZero_isFirstPoint_facingFirstSegment() {
        double[][] pts = {{0, 64, 0}, {4, 64, 0}};
        WalkSample s = PlayerPuppetService.sampleWalk(pts, 0.0);
        assertEquals(0.0, s.x(), EPS);
        assertEquals(64.0, s.y(), EPS);
        assertEquals(0.0, s.z(), EPS);
        assertEquals(4.0, s.dirX(), EPS, "faces along the first segment (+x)");
        assertEquals(0.0, s.dirZ(), EPS);
        assertFalse(s.arrived());
    }

    @Test
    void sampleWalk_midSegment_lerpsPosition() {
        double[][] pts = {{0, 64, 0}, {4, 64, 0}};
        WalkSample s = PlayerPuppetService.sampleWalk(pts, 1.0);
        assertEquals(1.0, s.x(), EPS, "quarter along a length-4 segment at distance 1");
        assertEquals(64.0, s.y(), EPS);
        assertFalse(s.arrived());
    }

    @Test
    void sampleWalk_pastEnd_clampsToLast_andArrived() {
        double[][] pts = {{0, 64, 0}, {4, 64, 0}};
        WalkSample s = PlayerPuppetService.sampleWalk(pts, 99.0);
        assertEquals(4.0, s.x(), EPS);
        assertEquals(0.0, s.z(), EPS);
        assertTrue(s.arrived(), "distance past the total length arrives");
    }

    @Test
    void sampleWalk_secondSegment_switchesHeading() {
        double[][] pts = {{0, 64, 0}, {2, 64, 0}, {2, 64, 3}};
        WalkSample s = PlayerPuppetService.sampleWalk(pts, 3.5);
        assertEquals(2.0, s.x(), EPS);
        assertEquals(1.5, s.z(), EPS, "1.5 along the second (length-3) segment");
        assertEquals(0.0, s.dirX(), EPS, "now heading along +z");
        assertEquals(3.0, s.dirZ(), EPS);
        assertFalse(s.arrived());
    }

    @Test
    void sampleWalk_verticalLerp_interpolatesY() {
        double[][] pts = {{0, 64, 0}, {0, 66, 0}};
        WalkSample s = PlayerPuppetService.sampleWalk(pts, 1.0);
        assertEquals(65.0, s.y(), EPS, "halfway up a 2-block vertical segment");
        // A purely vertical segment yields no horizontal heading (yaw is left unchanged by the caller).
        assertEquals(0.0, s.dirX(), EPS);
        assertEquals(0.0, s.dirZ(), EPS);
    }

    @Test
    void sampleWalk_singlePoint_isThatPoint_arrived() {
        WalkSample s = PlayerPuppetService.sampleWalk(new double[][] {{7, 64, 9}}, 5.0);
        assertEquals(7.0, s.x(), EPS);
        assertEquals(9.0, s.z(), EPS);
        assertTrue(s.arrived());
    }

    // ==================== yaw from heading (through the engine's pure PhysicsMath) ====================

    @Test
    void yawFromHeading_cardinalDirections() {
        // headingFromDirection(x, z) = atan2(-x, -z). Verify the four cardinals.
        assertEquals((float) (-Math.PI / 2), PhysicsMath.headingFromDirection(1, 0), 0.02f, "+x");
        assertEquals((float) (Math.PI / 2), PhysicsMath.headingFromDirection(-1, 0), 0.02f, "-x");
        assertEquals((float) Math.PI, Math.abs(PhysicsMath.headingFromDirection(0, 1)), 0.02f, "+z");
        assertEquals(0f, PhysicsMath.headingFromDirection(0, -1), 0.02f, "-z");
    }

    @Test
    void yawFromHeading_usesSampleDirection() {
        // The exact wiring walkTick performs: sampleWalk's dir fed into headingFromDirection.
        double[][] pts = {{0, 64, 0}, {0, 64, 5}}; // heading +z
        WalkSample s = PlayerPuppetService.sampleWalk(pts, 1.0);
        float yaw = PhysicsMath.headingFromDirection(s.dirX(), s.dirZ());
        assertEquals((float) Math.PI, Math.abs(yaw), 0.02f, "a +z segment faces yaw = PI");
    }

    // ==================== isArrived (arrival epsilon contract) ====================

    @Test
    void isArrived_withinEpsilon_isTrue() {
        assertTrue(PlayerPuppetService.isArrived(4.99, 5.0, 0.05), "within epsilon of the end has arrived");
        assertTrue(PlayerPuppetService.isArrived(5.0, 5.0, 0.0), "exactly at the end has arrived");
        assertTrue(PlayerPuppetService.isArrived(6.0, 5.0, 0.05), "past the end has arrived");
    }

    @Test
    void isArrived_beyondEpsilon_isFalse() {
        assertFalse(PlayerPuppetService.isArrived(4.9, 5.0, 0.05), "still more than epsilon short");
        assertFalse(PlayerPuppetService.isArrived(0.0, 5.0, 0.05), "at the start has not arrived");
    }
}
