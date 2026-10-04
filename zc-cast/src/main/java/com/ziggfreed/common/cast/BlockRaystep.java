package com.ziggfreed.common.cast;

import java.util.function.IntPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.world.SectionBlockCursor;

/**
 * Shared block-walking utilities for ability targeting (teleport / dash / ground-aoe
 * and any archetype that needs a look-ray block query).
 *
 * <p>Both methods step the look ray in fixed increments, reading each step's block off the
 * world's loaded chunk sections through zc-core's {@link SectionBlockCursor} (the read both the
 * live server and Update 7 keep; Update 7 deletes {@code World.getBlock}). Empty, non-Solid and
 * unloaded blocks are treated as clear, and a chunk that is not loaded is never loaded; the first
 * {@link BlockMaterial#Solid} hit is the stop point.
 *
 * <p>{@link #clearDistance} returns a distance scalar, optionally pulled back by
 * {@code wallPullback} so the caster lands shy of the wall. {@link #hitPosition}
 * returns the precise hit Vector3d (no pullback) - used for ground-zone landing.
 *
 * <p>Stateless static utility; world-thread (reads blocks off the world). All
 * distance / step / pullback values are caller-supplied parameters. The walk itself sits
 * behind a package-private block-id source and solid test, so it is unit-tested without a world.
 */
public final class BlockRaystep {

    /** The walk's answer when no step meets a solid block. */
    private static final double NO_HIT = -1.0;

    private BlockRaystep() {}

    /**
     * The block id at a world cell: {@code BlockType.EMPTY_ID} for air and for a cell the read cannot
     * see. Package-private: production reads a {@code SectionBlockCursor}, the test a fake world.
     */
    @FunctionalInterface
    interface BlockIds {
        int at(int x, int y, int z);
    }

    /**
     * Walk the ray from {@code origin} along {@code direction} in
     * {@code stepIncrement}-block steps. On the first Solid block hit, return
     * {@code (t - wallPullback)}. If the full ray clears, return
     * {@code maxDistance}.
     */
    public static double clearDistance(@Nullable World world,
                                       @Nonnull Vector3d origin,
                                       @Nonnull Vector3d direction,
                                       double maxDistance,
                                       double stepIncrement,
                                       double wallPullback) {
        if (world == null || maxDistance <= 0.0 || stepIncrement <= 0.0) {
            return Math.max(0.0, maxDistance);
        }
        return clearDistanceAlong(blocksOf(world), BlockRaystep::isSolid, origin, direction,
                maxDistance, stepIncrement, wallPullback);
    }

    /**
     * Walk the ray from {@code origin} along {@code direction} in
     * {@code stepIncrement}-block steps. On the first Solid block hit, return
     * the exact hit point. If the full ray clears, return the ray-end point.
     */
    @Nonnull
    public static Vector3d hitPosition(@Nullable World world,
                                       @Nonnull Vector3d origin,
                                       @Nonnull Vector3d direction,
                                       double maxDistance,
                                       double stepIncrement) {
        if (world == null || maxDistance <= 0.0 || stepIncrement <= 0.0) {
            return new Vector3d(origin.x, origin.y, origin.z);
        }
        return hitPositionAlong(blocksOf(world), BlockRaystep::isSolid, origin, direction,
                maxDistance, stepIncrement);
    }

    /**
     * {@link #clearDistance} over a block-id source and a solid test. The caller has already guarded a
     * positive {@code maxDistance} and {@code stepIncrement}.
     */
    static double clearDistanceAlong(@Nonnull BlockIds blocks, @Nonnull IntPredicate solid,
                                     @Nonnull Vector3d origin, @Nonnull Vector3d direction,
                                     double maxDistance, double stepIncrement, double wallPullback) {
        double t = firstSolidStep(blocks, solid, origin, direction, maxDistance, stepIncrement);
        return t == NO_HIT ? maxDistance : Math.max(0.0, t - Math.max(0.0, wallPullback));
    }

    /**
     * {@link #hitPosition} over a block-id source and a solid test: the sample point of the first solid
     * step, else the ray's end. The caller has already guarded a positive {@code maxDistance} and
     * {@code stepIncrement}.
     */
    @Nonnull
    static Vector3d hitPositionAlong(@Nonnull BlockIds blocks, @Nonnull IntPredicate solid,
                                     @Nonnull Vector3d origin, @Nonnull Vector3d direction,
                                     double maxDistance, double stepIncrement) {
        double t = firstSolidStep(blocks, solid, origin, direction, maxDistance, stepIncrement);
        double d = t == NO_HIT ? maxDistance : t;
        return new Vector3d(
                origin.x + direction.x * d,
                origin.y + direction.y * d,
                origin.z + direction.z * d);
    }

    /**
     * The distance of the first step whose cell is solid, or {@code NO_HIT}. Each sample's cell is the
     * floor of its coordinates (a sample at x = -2.5 is in cell -3); an empty cell never reaches the
     * solid test.
     */
    private static double firstSolidStep(@Nonnull BlockIds blocks, @Nonnull IntPredicate solid,
                                         @Nonnull Vector3d origin, @Nonnull Vector3d direction,
                                         double maxDistance, double stepIncrement) {
        for (double t = stepIncrement; t <= maxDistance; t += stepIncrement) {
            int blockId = blocks.at(
                    (int) Math.floor(origin.x + direction.x * t),
                    (int) Math.floor(origin.y + direction.y * t),
                    (int) Math.floor(origin.z + direction.z * t));
            if (blockId != BlockType.EMPTY_ID && solid.test(blockId)) {
                return t;
            }
        }
        return NO_HIT;
    }

    /** A loaded block type whose material is {@code BlockMaterial.Solid}; an id no block answers is clear. */
    private static boolean isSolid(int blockId) {
        BlockType type = BlockType.getAssetMap().getAsset(blockId);
        return type != null && type.getMaterial() == BlockMaterial.Solid;
    }

    /** The block ids along one ray, read off the world's loaded chunk sections. */
    @Nonnull
    private static BlockIds blocksOf(@Nonnull World world) {
        SectionBlockCursor cursor = SectionBlockCursor.of(world);
        return cursor::blockId;
    }
}
