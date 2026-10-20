package com.ziggfreed.common.world.gather;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * The walks a gather shape takes out from its first block, over a cell test the caller supplies, so every bound is
 * pure and tested without a world. Each answers cells in the order they would break (nearest first), never the first
 * block itself, never more than its cap, and never past {@link #REACH} blocks from the first block on any axis, so a
 * walk over a huge connected run (a cave wall of one stone, a forest of one wood) stops at its bound. A connected walk
 * visits at most {@link #VISIT_LIMIT} cells whatever it finds.
 */
final class ShapeWalks {

    /** The farthest any walk reaches from its first block on any one axis. */
    static final int REACH = 24;

    /** The most cells a connected walk looks at. */
    static final int VISIT_LIMIT = 4096;

    /** The hard cap on what any one shape takes. */
    static final int MAX_COUNT = 128;

    /** Does a cell qualify? Coordinates are world coordinates. */
    @FunctionalInterface
    interface Cells {
        boolean takes(int x, int y, int z);
    }

    private ShapeWalks() {
    }

    /** {@code requested} held in 0..{@link #MAX_COUNT}. */
    static int capped(double requested) {
        if (!(requested > 0.0)) {
            return 0;
        }
        return (int) Math.min(MAX_COUNT, Math.floor(requested));
    }

    /**
     * Every qualifying cell joined to {@code (ox, oy, oz)} through qualifying cells, corner to corner (the 26 around
     * each), breadth first so the nearest come first, at most {@code max} of them.
     */
    @Nonnull
    static List<int[]> connected(int ox, int oy, int oz, int max, @Nonnull Cells cells) {
        List<int[]> out = new ArrayList<>();
        if (max <= 0) {
            return out;
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen.add(pack(ox, oy, oz));
        queue.add(new int[] {ox, oy, oz});
        int visited = 0;
        while (!queue.isEmpty() && out.size() < max && visited < VISIT_LIMIT) {
            int[] at = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int x = at[0] + dx;
                        int y = at[1] + dy;
                        int z = at[2] + dz;
                        if (Math.abs(x - ox) > REACH || Math.abs(y - oy) > REACH || Math.abs(z - oz) > REACH
                                || !seen.add(pack(x, y, z))) {
                            continue;
                        }
                        if (++visited > VISIT_LIMIT) {
                            return out;
                        }
                        if (cells.takes(x, y, z)) {
                            int[] cell = {x, y, z};
                            out.add(cell);
                            queue.add(cell);
                            if (out.size() >= max) {
                                return out;
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The leaves around {@code logs}: qualifying cells joined to any log or to each other, within {@link #REACH} of
     * the first block, at most {@code max}. The logs themselves are not answered.
     */
    @Nonnull
    static List<int[]> canopy(int ox, int oy, int oz, @Nonnull List<int[]> logs, int max, @Nonnull Cells leaves) {
        List<int[]> out = new ArrayList<>();
        if (max <= 0) {
            return out;
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen.add(pack(ox, oy, oz));
        queue.add(new int[] {ox, oy, oz});
        for (int[] log : logs) {
            if (seen.add(pack(log[0], log[1], log[2]))) {
                queue.add(log);
            }
        }
        int visited = 0;
        while (!queue.isEmpty() && out.size() < max && visited < VISIT_LIMIT) {
            int[] at = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = at[0] + dx;
                        int y = at[1] + dy;
                        int z = at[2] + dz;
                        if (Math.abs(x - ox) > REACH || Math.abs(y - oy) > REACH || Math.abs(z - oz) > REACH
                                || !seen.add(pack(x, y, z))) {
                            continue;
                        }
                        if (++visited > VISIT_LIMIT) {
                            return out;
                        }
                        if (leaves.takes(x, y, z)) {
                            int[] cell = {x, y, z};
                            out.add(cell);
                            queue.add(cell);
                            if (out.size() >= max) {
                                return out;
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The cells {@code offsets} names around {@code (ox, oy, oz)}, each {@code {forward, up, right}} in the frame of a
     * player facing along {@code (fx, 0, fz)} (one of the four flat directions), in the order written, qualifying
     * ones only, at most {@code max}.
     */
    @Nonnull
    static List<int[]> offsets(int ox, int oy, int oz, int fx, int fz, @Nonnull int[][] offsets, int max,
            @Nonnull Cells cells) {
        List<int[]> out = new ArrayList<>();
        // Facing (fx, fz) seen from above, the right hand points along (-fz, fx): north (0, -1) has east (1, 0).
        int rx = -fz;
        int rz = fx;
        for (int[] offset : offsets) {
            if (out.size() >= max) {
                break;
            }
            int forward = offset[0];
            int up = offset[1];
            int right = offset[2];
            if (forward == 0 && up == 0 && right == 0) {
                continue;
            }
            int x = ox + forward * fx + right * rx;
            int y = oy + up;
            int z = oz + forward * fz + right * rz;
            if (cells.takes(x, y, z)) {
                out.add(new int[] {x, y, z});
            }
        }
        return out;
    }

    /**
     * The qualifying cells of the square {@code radius} blocks out around {@code (ox, oz)}, from layer {@code minY} to
     * {@code maxY}, nearest the first block first, at most {@code max}.
     */
    @Nonnull
    static List<int[]> area(int ox, int oy, int oz, int radius, int minY, int maxY, int max, @Nonnull Cells cells) {
        List<int[]> out = new ArrayList<>();
        int r = Math.max(0, Math.min(REACH, radius));
        int low = Math.max(oy - REACH, minY);
        int high = Math.min(oy + REACH, maxY);
        for (int y = low; y <= high; y++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0 && y == oy) {
                        continue;
                    }
                    if (cells.takes(ox + dx, y, oz + dz)) {
                        out.add(new int[] {ox + dx, y, oz + dz});
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt(c -> Math.abs(c[0] - ox) + Math.abs(c[1] - oy) + Math.abs(c[2] - oz)));
        return out.size() > max ? new ArrayList<>(out.subList(0, Math.max(0, max))) : out;
    }

    /**
     * The flat direction a player at {@code (px, pz)} faces a block centred at {@code (bx, bz)} along: the larger of
     * the two horizontal axes, signed; north {@code (0, -1)} when they stand on the block.
     */
    @Nonnull
    static int[] facing(double px, double pz, double bx, double bz) {
        double dx = bx - px;
        double dz = bz - pz;
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
            return new int[] {0, -1};
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new int[] {dx > 0 ? 1 : -1, 0};
        }
        return new int[] {0, dz > 0 ? 1 : -1};
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
