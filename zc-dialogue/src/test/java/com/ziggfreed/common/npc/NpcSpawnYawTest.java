package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAuthoring;

/**
 * An authored {@code Yaw} is degrees and the engine reads a rotation in radians: the one conversion
 * the spawn path goes through, and the inverse the authoring commands write a live yaw back with.
 */
class NpcSpawnYawTest {

    private static final float EPS = 1e-5f;

    @Test
    void yawRadiansConvertsDegrees() {
        assertEquals(0f, NpcSpawnService.yawRadians(0f), EPS);
        assertEquals((float) (Math.PI / 2), NpcSpawnService.yawRadians(90f), EPS);
        assertEquals((float) Math.PI, NpcSpawnService.yawRadians(180f), EPS);
        assertEquals((float) (-Math.PI / 2), NpcSpawnService.yawRadians(-90f), EPS);
    }

    @Test
    void anAnchorWithYaw180SpawnsWithARotationOfPi() {
        Rotation3f rotation = NpcSpawnService.spawnRotation(180f);
        assertEquals((float) Math.PI, rotation.yaw(), EPS);
        assertEquals(0f, rotation.pitch(), EPS);
        assertEquals(0f, rotation.roll(), EPS);
    }

    @Test
    void aCapturedLiveYawIsWrittenBackInDegrees() {
        assertEquals(180.0, NpcPlacementAuthoring.yawDegrees((float) Math.PI), 1e-4);
        assertEquals(90.0, NpcPlacementAuthoring.yawDegrees(NpcSpawnService.yawRadians(90f)), 1e-4);
    }

    /**
     * The one helper every capture site writes a live facing through (/zignpc place, the admin page, a
     * consumer's alias): the rotation's yaw, radians as the engine keeps it, as the degrees a placement's
     * {@code Yaw} reads, to one decimal so the written file reads like something authored.
     */
    @Test
    void aLiveRotationIsCapturedAsDegreesToOneDecimal() {
        assertEquals(90.0, NpcPlacementAuthoring.capturedYaw(NpcSpawnService.spawnRotation(90f)), 1e-9,
                "a facing spawned at 90 degrees is captured as 90, not as its float radians' long tail");
        assertEquals(57.3, NpcPlacementAuthoring.capturedYaw(new Rotation3f(0f, 1f, 0f)), 1e-9,
                "one radian is 57.3 degrees, never 1");
        assertEquals(-135.0, NpcPlacementAuthoring.capturedYaw(new Rotation3f(0f, (float) (-3 * Math.PI / 4), 0f)),
                1e-9);
    }
}
