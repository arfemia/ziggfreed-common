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
}
