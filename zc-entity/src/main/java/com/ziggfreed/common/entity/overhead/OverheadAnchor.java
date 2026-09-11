package com.ziggfreed.common.entity.overhead;

import javax.annotation.Nonnull;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Where a marker sits relative to its host, as arithmetic: the host's position, how tall the host
 * is, and the look's own offset. Pure and primitive-typed so the placement rule is unit-tested
 * without an entity in sight.
 *
 * <p>The anchor is the TOP of the host: the taller of its eye height and its bounding box, which
 * for a character is a hair above the eyes and for a creature with no eye line is its box. A host
 * that reports neither is given a human height rather than nothing, so a cue never sits in its
 * host's feet.
 */
final class OverheadAnchor {

    /** The height assumed for a host whose model and box both answer zero, in blocks. */
    static final double FALLBACK_HEIGHT = 1.8;

    private OverheadAnchor() {
    }

    /** The anchor height above the host's position: the taller reading, or the fallback for none. */
    static double anchorHeight(double eyeHeight, double boundingBoxHeight) {
        double tallest = Math.max(eyeHeight, boundingBoxHeight);
        return tallest > 0d ? tallest : FALLBACK_HEIGHT;
    }

    /** The marker's position for a host standing at {@code host} with {@code anchorHeight} above it. */
    @Nonnull
    static Vector3d target(@Nonnull Vector3dc host, double anchorHeight, double offsetX, double offsetY,
                           double offsetZ) {
        return new Vector3d(host.x() + offsetX, host.y() + anchorHeight + offsetY, host.z() + offsetZ);
    }

    /** True when {@code current} is more than {@code epsilon} blocks from {@code target}. */
    static boolean moved(@Nonnull Vector3dc current, @Nonnull Vector3dc target, double epsilon) {
        double dx = current.x() - target.x();
        double dy = current.y() - target.y();
        double dz = current.z() - target.z();
        return dx * dx + dy * dy + dz * dz > epsilon * epsilon;
    }
}
