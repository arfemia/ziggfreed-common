package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Picks floor-snapped runtime spawn positions on procedural, uneven terrain. Every
 * position's Y is resolved through {@link SurfaceProbe#standableY(World, int, int, int)}
 * so a runtime-placed entity/prop lands on the genuine surface instead of a hardcoded Y.
 *
 * <p><b>World-thread only</b>: {@link SurfaceProbe} reads loaded blocks via
 * {@link World#getBlock(int, int, int)}, so the caller MUST already be on the world
 * thread (inside a {@code world.execute(...)} hop). Each Y read is try-guarded inside
 * {@code SurfaceProbe}; an unloaded chunk or out-of-range coordinate degrades to the
 * caller-supplied {@code fallbackY}, never a throw into the caller.
 *
 * <p>Generic and config-free: the caller supplies all geometry (center, radius, count,
 * fallback Y) and, for the deterministic variants, the random {@code seed}. This class
 * NEVER calls {@link Math#random()} so a given seed always reproduces the same point.
 *
 * <p>Each method has a {@code skipBlockKeys} overload that forwards a set of foliage block keys to
 * {@link SurfaceProbe#standableY(World, int, int, int, Set)}, so a spawn in a treed grove snaps to the
 * genuine GROUND under runtime tree decoration (trunk/branch/leaf) instead of onto the canopy. The
 * no-skip overloads delegate to these with {@code null} (no skips).
 */
public final class SpawnPlacement {

    private SpawnPlacement() {
    }

    /**
     * {@code count} positions evenly spaced (by angle) on a ring of {@code radius} around
     * {@code (cx, cz)}, each floor-snapped to its standable surface Y.
     *
     * @param world     the world (read on its own thread)
     * @param cx        ring center world X
     * @param cz        ring center world Z
     * @param radius    ring radius (block units); a non-positive radius stacks every point on the center
     * @param count     number of positions to produce; {@code <= 0} yields an empty list
     * @param fallbackY standable Y used for any point whose surface probe finds no solid block
     * @return a list of {@code count} floor-snapped positions (never {@code null}, possibly empty)
     */
    @Nonnull
    public static List<Vector3d> ringAround(@Nonnull World world, double cx, double cz, double radius,
                                            int count, int fallbackY) {
        return ringAround(world, cx, cz, radius, count, fallbackY, null);
    }

    /**
     * {@link #ringAround(World, double, double, double, int, int)} that additionally SKIPS any block
     * whose registered key is in {@code skipBlockKeys} when resolving each point's surface Y, so a ring
     * spawned in a treed grove snaps to the genuine GROUND under runtime tree decoration instead of onto
     * a trunk/branch/leaf canopy. Delegates each point to the foliage-skipping
     * {@link SurfaceProbe#standableY(World, int, int, int, Set)}.
     *
     * @param skipBlockKeys block-type keys to treat as non-surface (skip), or {@code null}/empty for none
     *                      (identical behavior to the no-skip overload)
     */
    @Nonnull
    public static List<Vector3d> ringAround(@Nonnull World world, double cx, double cz, double radius,
                                            int count, int fallbackY, @Nullable Set<String> skipBlockKeys) {
        List<Vector3d> out = new ArrayList<>(Math.max(0, count));
        if (count <= 0) {
            return out;
        }
        double step = (2.0 * Math.PI) / count;
        for (int i = 0; i < count; i++) {
            double angle = step * i;
            double x = cx + Math.cos(angle) * radius;
            double z = cz + Math.sin(angle) * radius;
            out.add(snapToSurface(world, x, z, fallbackY, skipBlockKeys));
        }
        return out;
    }

    /**
     * A single position at a deterministic-seeded random angle and radius in
     * {@code [minRadius, maxRadius]} around the player at {@code (px, pz)}, floor-snapped to
     * its standable surface Y. The same {@code seed} always reproduces the same point.
     *
     * @param world     the world (read on its own thread)
     * @param px        player world X
     * @param pz        player world Z
     * @param minRadius minimum distance from the player (block units); clamped to {@code >= 0}
     * @param maxRadius maximum distance from the player; treated as {@code minRadius} if smaller
     * @param seed      caller-supplied RNG seed (NO {@link Math#random()} is used internally)
     * @param fallbackY standable Y used if the surface probe finds no solid block at the picked spot
     * @return a single floor-snapped position (never {@code null})
     */
    @Nonnull
    public static Vector3d nearPlayer(@Nonnull World world, double px, double pz, double minRadius,
                                      double maxRadius, long seed, int fallbackY) {
        return nearPlayer(world, px, pz, minRadius, maxRadius, seed, fallbackY, null);
    }

    /**
     * {@link #nearPlayer(World, double, double, double, double, long, int)} that additionally SKIPS any
     * block whose registered key is in {@code skipBlockKeys} when resolving the surface Y, so a near-player
     * spawn in a treed grove snaps to the genuine GROUND under runtime tree decoration instead of onto a
     * trunk/branch/leaf canopy. Still fully deterministic for a given {@code seed}. Delegates to the
     * foliage-skipping {@link SurfaceProbe#standableY(World, int, int, int, Set)}.
     *
     * @param skipBlockKeys block-type keys to treat as non-surface (skip), or {@code null}/empty for none
     *                      (identical behavior to the no-skip overload)
     */
    @Nonnull
    public static Vector3d nearPlayer(@Nonnull World world, double px, double pz, double minRadius,
                                      double maxRadius, long seed, int fallbackY,
                                      @Nullable Set<String> skipBlockKeys) {
        double lo = Math.max(0.0, minRadius);
        double hi = Math.max(lo, maxRadius);
        Random rng = new Random(seed);
        double angle = rng.nextDouble() * 2.0 * Math.PI;
        double radius = lo + rng.nextDouble() * (hi - lo);
        double x = px + Math.cos(angle) * radius;
        double z = pz + Math.sin(angle) * radius;
        return snapToSurface(world, x, z, fallbackY, skipBlockKeys);
    }

    /**
     * Floor-snap a single {@code (x, z)} to its standable surface, as a {@link Vector3d}. Thin
     * wrapper over {@link SurfaceProbe#standableY(World, int, int, int)} (which is world-thread
     * only and try-guarded); the X/Z are passed through verbatim (the engine block grid is read
     * at their floored integer coordinates).
     *
     * @param world     the world (read on its own thread)
     * @param x         world X
     * @param z         world Z
     * @param fallbackY standable Y used if no solid block is found or a read fails
     * @return {@code (x, standableY, z)} (never {@code null})
     */
    @Nonnull
    public static Vector3d snapToSurface(@Nonnull World world, double x, double z, int fallbackY) {
        return snapToSurface(world, x, z, fallbackY, null);
    }

    /**
     * {@link #snapToSurface(World, double, double, int)} that additionally SKIPS any block whose registered
     * key is in {@code skipBlockKeys}, so the snap lands on the genuine GROUND under runtime tree decoration
     * (trunk/branch/leaf) instead of on the canopy. Thin wrapper over the foliage-skipping
     * {@link SurfaceProbe#standableY(World, int, int, int, Set)} (world-thread only, try-guarded).
     *
     * @param skipBlockKeys block-type keys to treat as non-surface (skip), or {@code null}/empty for none
     *                      (identical behavior to the no-skip overload)
     */
    @Nonnull
    public static Vector3d snapToSurface(@Nonnull World world, double x, double z, int fallbackY,
                                         @Nullable Set<String> skipBlockKeys) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int y = SurfaceProbe.standableY(world, bx, bz, fallbackY, skipBlockKeys);
        return new Vector3d(x, y, z);
    }
}
