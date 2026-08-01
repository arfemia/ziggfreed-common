package com.ziggfreed.common.interaction.target;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.server.core.universe.world.World;

import com.ziggfreed.common.cast.BlockRaystep;

/**
 * Block line-of-sight between two points, over {@code cast.BlockRaystep}.
 *
 * <p>Convention inherited from {@link BlockRaystep} (and matching the engine's own
 * {@code HorizontalSelector} line-of-sight provider on unloaded chunks): a null world, an
 * unloaded chunk, or an off-map block counts as CLEAR - line of sight never invents an
 * obstruction it cannot see. Only a {@code BlockMaterial.Solid} block blocks it.
 *
 * <p><b>Honest limitation.</b> This is a block-GRID test with no per-hitbox detail boxes,
 * unlike the engine's own finer {@code HorizontalSelector} line-of-sight provider (which
 * can test against a target's actual collision shape). A shot that clears the block grid
 * but would have grazed a fine detail box is reported clear here.
 *
 * <p>World-thread. Never throws.
 */
public final class LineOfSight {

    private static final double DEFAULT_STEP = 0.3;

    private LineOfSight() {}

    /** Step increment 0.3 blocks. */
    public static boolean clear(@Nullable World world, @Nullable Vector3d from, @Nullable Vector3d to) {
        return clear(world, from, to, DEFAULT_STEP);
    }

    /** {@code step <= 0} falls back to 0.3. */
    public static boolean clear(@Nullable World world, @Nullable Vector3d from, @Nullable Vector3d to, double step) {
        if (from == null || to == null) return true;
        double distance = from.distance(to);
        if (distance <= 1e-6) return true;
        double useStep = step <= 0.0 ? DEFAULT_STEP : step;
        Vector3d dir = new Vector3d(
                (to.x - from.x) / distance,
                (to.y - from.y) / distance,
                (to.z - from.z) / distance);
        double cleared = BlockRaystep.clearDistance(world, from, dir, distance, useStep, 0.0);
        return cleared >= distance - 1e-6;
    }
}
