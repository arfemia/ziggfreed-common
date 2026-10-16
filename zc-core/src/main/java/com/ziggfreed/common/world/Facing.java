package com.ziggfreed.common.world;

import javax.annotation.Nonnull;

import com.hypixel.hytale.math.vector.Rotation3f;

/**
 * The one conversion from an authored facing to the engine's. Every authored facing in the library is a yaw in
 * DEGREES (an NPC placement's or anchor's {@code Yaw}, a dungeon's {@code Area.EntryYaw}); the engine reads
 * radians, so it is converted once, here, where the rotation is built.
 *
 * <p>Yaw 0 faces north, toward negative Z (the engine's look direction is {@code (-sin yaw, 0, -cos yaw)});
 * 180 faces south, toward positive Z.
 */
public final class Facing {

    private Facing() {
    }

    /** Pure: an authored facing in DEGREES as the radians the engine reads a {@link Rotation3f} in. */
    public static float yawRadians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    /** Pure: the rotation for an authored yaw in degrees (pitch and roll 0). */
    @Nonnull
    public static Rotation3f rotation(float yawDegrees) {
        return new Rotation3f(0.0f, yawRadians(yawDegrees), 0.0f);
    }
}
