package com.ziggfreed.common.cast;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Shared block-walking utilities for ability targeting (teleport / dash / ground-aoe
 * and any archetype that needs a look-ray block query).
 *
 * <p>Both methods step the look ray in fixed increments calling {@link World#getBlock}
 * per step. Empty / non-Solid / unloaded blocks are treated as clear; the first
 * {@link BlockMaterial#Solid} hit is the stop point.
 *
 * <p>{@link #clearDistance} returns a distance scalar, optionally pulled back by
 * {@code wallPullback} so the caster lands shy of the wall. {@link #hitPosition}
 * returns the precise hit Vector3d (no pullback) - used for ground-zone landing.
 *
 * <p>Stateless static utility; world-thread (reads blocks off the world). All
 * distance / step / pullback values are caller-supplied parameters.
 */
public final class BlockRaystep {

    private BlockRaystep() {}

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
        for (double t = stepIncrement; t <= maxDistance; t += stepIncrement) {
            int bx = (int) Math.floor(origin.x + direction.x * t);
            int by = (int) Math.floor(origin.y + direction.y * t);
            int bz = (int) Math.floor(origin.z + direction.z * t);
            int blockId;
            try {
                blockId = world.getBlock(bx, by, bz);
            } catch (Throwable ignored) {
                // Chunk unloaded / off-map - treat as clear and keep stepping.
                continue;
            }
            if (blockId == 0) continue;
            BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
            if (blockType == null) continue;
            if (blockType.getMaterial() == BlockMaterial.Solid) {
                return Math.max(0.0, t - Math.max(0.0, wallPullback));
            }
        }
        return maxDistance;
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
        for (double t = stepIncrement; t <= maxDistance; t += stepIncrement) {
            double px = origin.x + direction.x * t;
            double py = origin.y + direction.y * t;
            double pz = origin.z + direction.z * t;
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(py);
            int bz = (int) Math.floor(pz);
            int blockId;
            try {
                blockId = world.getBlock(bx, by, bz);
            } catch (Throwable ignored) {
                continue;
            }
            if (blockId == 0) continue;
            BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
            if (blockType == null) continue;
            if (blockType.getMaterial() == BlockMaterial.Solid) {
                return new Vector3d(px, py, pz);
            }
        }
        return new Vector3d(
                origin.x + direction.x * maxDistance,
                origin.y + direction.y * maxDistance,
                origin.z + direction.z * maxDistance);
    }
}
