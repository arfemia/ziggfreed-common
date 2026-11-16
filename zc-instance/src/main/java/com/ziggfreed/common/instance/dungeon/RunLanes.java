package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;

import com.ziggfreed.common.instance.dungeon.layout.Cell;

/**
 * Where a generated dungeon's runs stand in a copy, pure math with no engine type. A generated copy's areas are
 * lanes along X, lane {@code a}'s line at {@code x = a * Spacing}; each party a lane takes gets a run of its own,
 * run {@code r}'s region (the layout plan's {@code Bounds}) centred on that line at {@code z = (r + 1) * RunStride},
 * so no run ever stands on ground an earlier one used, and none on the origin chunk a fresh world loads first.
 *
 * <ul>
 *   <li>{@code RunStride} (default {@link #DEFAULT_RUN_STRIDE}) is floored at the plan's {@code Bounds.Z} plus
 *       {@link #VIEW_MARGIN}, and {@code Spacing} at its {@code Bounds.X} plus the margin, so a player standing at
 *       the edge of one region sees nothing of the next.</li>
 *   <li>{@code MaxRunsPerLane} (default {@link #DEFAULT_MAX_RUNS_PER_LANE}) is capped so the far edge of the
 *       furthest region stays within {@link #FAR_LIMIT} blocks of the origin, where a float position still holds
 *       to a fraction of a block; a lane that has served them all is spent, and the copy takes no party into it
 *       again. {@code AreasPerCopy} is capped the same way along X ({@link #lanes}).</li>
 *   <li>A region's frame (the layout's own coordinates, 0 to {@code Bounds} less one on each axis) starts
 *       {@code Bounds / 2} before its centre on X and Z, and at {@link AreaLayout#AREA_Y} on Y, so a plan taller
 *       than {@link #WORLD_TOP} less that row reaches past the world's top ({@link #fitsHeight}).</li>
 * </ul>
 */
public final class RunLanes {

    /** The blocks a player can see across, plus a chunk: 33 chunks of 32 (the engine's view radius tops at 32). */
    public static final int VIEW_MARGIN = 33 * 32;

    /** How far from the origin a region may reach, in blocks: 2^17, where a float still holds a sixty-fourth. */
    public static final int FAR_LIMIT = 1 << 17;

    /** How far apart two runs of one lane are centred, in blocks, when a dungeon says nothing (lead's call). */
    public static final int DEFAULT_RUN_STRIDE = 2048;

    /** How many runs one lane serves before it is spent, when a dungeon says nothing (lead's call). */
    public static final int DEFAULT_MAX_RUNS_PER_LANE = 32;

    /**
     * The highest row a generated room may stand on: a dungeon copy's chunks hold rows 0 to 319 (ten sections of 32,
     * the height the engine's world generators build).
     */
    public static final int WORLD_TOP = 319;

    private RunLanes() {
    }

    /** The lanes' spacing for a plan {@code boundsX} wide: {@code authored}, floored at the plan's width plus the margin. */
    public static int spacing(int authored, int boundsX) {
        return Math.max(Math.max(1, authored), Math.max(1, boundsX) + VIEW_MARGIN);
    }

    /**
     * The run stride for a plan {@code boundsZ} deep: {@code authored} ({@link #DEFAULT_RUN_STRIDE} when less than 1),
     * floored at the plan's depth plus the margin.
     */
    public static int runStride(int authored, int boundsZ) {
        int wanted = authored < 1 ? DEFAULT_RUN_STRIDE : authored;
        return Math.max(wanted, Math.max(1, boundsZ) + VIEW_MARGIN);
    }

    /**
     * How many runs one lane serves: {@code authored} ({@link #DEFAULT_MAX_RUNS_PER_LANE} when less than 1), capped so
     * the furthest region's far edge stays within {@link #FAR_LIMIT}, and never less than 1.
     */
    public static int maxRuns(int authored, int runStride, int boundsZ) {
        int wanted = authored < 1 ? DEFAULT_MAX_RUNS_PER_LANE : authored;
        return Math.max(1, Math.min(wanted, farthestRuns(runStride, boundsZ)));
    }

    /** How many regions of a lane fit before the far edge of the last one would pass {@link #FAR_LIMIT}. */
    static int farthestRuns(int runStride, int boundsZ) {
        int depth = Math.max(1, boundsZ);
        int reach = depth - depth / 2;
        return (FAR_LIMIT - reach) / Math.max(1, runStride);
    }

    /**
     * How many lanes a copy holds: {@code authored} (at least 1), capped so the far edge of the furthest lane's regions
     * (lane {@code n - 1}'s line at {@code (n - 1) * spacing}, half the plan's width either side) stays within
     * {@link #FAR_LIMIT}, the bound {@link #maxRuns} keeps along Z; never less than 1.
     */
    public static int lanes(int authored, int spacing, int boundsX) {
        int width = Math.max(1, boundsX);
        int reach = width - width / 2;
        int fit = (FAR_LIMIT - reach) / Math.max(1, spacing) + 1;
        return Math.max(1, Math.min(Math.max(1, authored), fit));
    }

    /** Whether a plan {@code boundsY} tall, its frame starting at {@link AreaLayout#AREA_Y}, stays at or below {@link #WORLD_TOP}. */
    public static boolean fitsHeight(int boundsY) {
        return AreaLayout.AREA_Y + Math.max(1, boundsY) - 1 <= WORLD_TOP;
    }

    /** The block at the centre of run {@code run}'s region in lane {@code lane}, on {@link AreaLayout#AREA_Y}. */
    @Nonnull
    public static Cell regionCentre(int lane, int run, int spacing, int runStride) {
        return new Cell(Math.max(0, lane) * spacing, AreaLayout.AREA_Y, (Math.max(0, run) + 1) * runStride);
    }

    /** Where the frame of run {@code run}'s region in lane {@code lane} starts: its centre less half the plan's bounds. */
    @Nonnull
    public static Cell frameOrigin(int lane, int run, int spacing, int runStride, @Nonnull Cell bounds) {
        Cell centre = regionCentre(lane, run, spacing, runStride);
        return new Cell(centre.x() - bounds.x() / 2, centre.y(), centre.z() - bounds.z() / 2);
    }
}
