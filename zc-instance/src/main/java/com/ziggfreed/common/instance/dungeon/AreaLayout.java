package com.ziggfreed.common.instance.dungeon;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.joml.Vector3i;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.world.Facing;

/**
 * Where the areas of one dungeon copy sit, fixed the moment the copy opens, so a dungeon file retuned while a
 * copy runs never moves an area under a party standing in it. The copy's spawn provider carries it
 * ({@link AreaSpawnProvider}), so the copy's saved config says it too.
 *
 * <p><b>The grid.</b> Area {@code i}'s prefab is pasted with its anchor at block
 * {@code ((i % 2) * Spacing, }{@link #AREA_Y}{@code , (i / 2) * Spacing)}: two areas side by side on X, the next
 * pair a row further on Z, so the default four make a square and the layout never depends on how many areas a
 * copy has. Every area sits at Y 0 or above.
 *
 * <p><b>An area's cell</b> is the column of the world within half a {@code Spacing} of its anchor on X and on Z,
 * at any height: a member who falls into the void below their area is still in it, and one who strays into the
 * next cell has left. A copy with one area has one cell, the whole world.
 *
 * <p><b>Arrival</b> is the anchor plus {@code Area.EntryOffset}, at the anchor block's centre on X and Z (so an
 * unauthored offset lands on the middle of the anchor block, not its corner), facing {@code Area.EntryYaw} degrees (unauthored: north, yaw 0), level.
 *
 * <p><b>A generated dungeon</b> ({@code Area.Layout}, {@code runStride} above 0) lays its areas out as lanes instead:
 * area {@code i}'s line runs along Z at {@code x = i * Spacing}, each run the lane serves stands in a region of its own
 * further along it ({@link RunLanes}), and its cell is the whole strip within half a {@code Spacing} of the line. A
 * player arrives at the run's entry room ({@code dungeon/gen/GeneratedRuns}); {@link #arrival} is only the lane's
 * fallback.
 */
public record AreaLayout(@Nonnull String dungeon, int areas, int spacing, double entryX, double entryY,
        double entryZ, float entryYaw, int runStride) {

    /** The height every area's prefab anchor sits at (lead's call, vetoable): 64 rows of room below it. */
    public static final int AREA_Y = 64;

    /** How many areas sit side by side on X before the next row on Z. */
    static final int ROW_WIDTH = 2;

    /**
     * How far below and above an area's anchor the clear looks, in blocks: far past any prefab and any fall,
     * since a world's height is not fixed (the engine's own height constants are deprecated for that reason).
     */
    private static final double CLEAR_REACH = 4096.0;

    public AreaLayout {
        dungeon = dungeon == null ? "" : dungeon.trim().toLowerCase(Locale.ROOT);
        areas = Math.max(1, areas);
        spacing = Math.max(1, spacing);
        runStride = Math.max(0, runStride);
    }

    /** A pasted dungeon's layout: its areas on the grid, no runs along a lane. */
    public AreaLayout(@Nonnull String dungeon, int areas, int spacing, double entryX, double entryY, double entryZ,
            float entryYaw) {
        this(dungeon, areas, spacing, entryX, entryY, entryZ, entryYaw, 0);
    }

    /**
     * The layout a new copy of {@code dungeon} opens with, filed under {@code dungeonId}. A generated dungeon's lanes
     * are spaced for its layout plan as loaded now, or for the plan's default bounds when it names none loaded.
     */
    @Nonnull
    public static AreaLayout of(@Nonnull String dungeonId, @Nonnull DungeonAsset dungeon) {
        return of(dungeonId, dungeon, dungeon.layoutBounds());
    }

    /**
     * The layout a new copy of {@code dungeon} opens with, filed under {@code dungeonId}; {@code bounds} is the size of
     * a generated dungeon's layout plan, which floors its lanes' spacing and run stride ({@link RunLanes}).
     */
    @Nonnull
    public static AreaLayout of(@Nonnull String dungeonId, @Nonnull DungeonAsset dungeon, @Nonnull Cell bounds) {
        Vec3 entry = dungeon.entryOffset();
        if (!dungeon.generated()) {
            return new AreaLayout(dungeonId, dungeon.areasPerCopy(), dungeon.spacing(), entry.effectiveX(),
                    entry.effectiveY(), entry.effectiveZ(), dungeon.entryYaw());
        }
        return new AreaLayout(dungeonId, dungeon.areasPerCopy(bounds), RunLanes.spacing(dungeon.spacing(), bounds.x()),
                entry.effectiveX(), entry.effectiveY(), entry.effectiveZ(), dungeon.entryYaw(),
                RunLanes.runStride(dungeon.runStride(), bounds.z()));
    }

    /** Whether the copy's areas are lanes of generated runs rather than pasted areas on the grid. */
    public boolean generated() {
        return runStride > 0;
    }

    /**
     * The layout copy {@code world} opened with, from its spawn provider; a world without one is a single area, the
     * whole world.
     */
    @Nonnull
    static AreaLayout ofCopy(@Nonnull World world) {
        ISpawnProvider provider = world.getWorldConfig().getSpawnProvider();
        if (provider instanceof AreaSpawnProvider areas) {
            return areas.layout();
        }
        return new AreaLayout(Areas.nameOf(world), 1, DungeonAsset.DEFAULT_SPACING, 0.0, 0.0, 0.0, 0f);
    }

    /**
     * Where an endless run's chest stands in {@code area}: {@code offset} from the spot its prefab is pasted at, or
     * with none written, {@link DungeonEndless#DEFAULT_CHEST_AHEAD} blocks in front of where a player arrives, on the
     * way they face.
     */
    @Nonnull
    Vector3d chestSpot(int area, @Nullable Vec3 offset) {
        Vector3i origin = origin(area);
        if (offset != null) {
            return new Vector3d(origin.x + 0.5 + offset.effectiveX(), origin.y + offset.effectiveY(),
                    origin.z + 0.5 + offset.effectiveZ());
        }
        double yaw = Math.toRadians(entryYaw);
        // Yaw 0 faces north (negative Z) and 90 west (negative X).
        double ahead = DungeonEndless.DEFAULT_CHEST_AHEAD;
        return new Vector3d(origin.x + 0.5 + entryX - Math.sin(yaw) * ahead, origin.y + entryY,
                origin.z + 0.5 + entryZ - Math.cos(yaw) * ahead);
    }

    /** Several parties share the copy, so each area is a cell of it rather than the whole world. */
    public boolean shared() {
        return areas > 1;
    }

    /** Is {@code area} one of this copy's areas? */
    public boolean has(int area) {
        return area >= 0 && area < areas;
    }

    /** The block area {@code area}'s prefab anchor is pasted at; for a generated copy, where its lane's line starts. */
    @Nonnull
    public Vector3i origin(int area) {
        int index = Math.max(0, area);
        if (generated()) {
            return new Vector3i(index * spacing, AREA_Y, 0);
        }
        return new Vector3i((index % ROW_WIDTH) * spacing, AREA_Y, (index / ROW_WIDTH) * spacing);
    }

    /** Where a player arriving in {@code area}, or respawning there, lands. */
    @Nonnull
    public Transform arrival(int area) {
        Vector3i origin = origin(area);
        return new Transform(new Vector3d(origin.x + 0.5 + entryX, origin.y + entryY, origin.z + 0.5 + entryZ),
                Facing.rotation(entryYaw));
    }

    /**
     * Is the spot {@code (x, z)} inside {@code area}'s cell? Always, in a copy with one area; in a generated copy, the
     * cell is the lane's whole strip along Z.
     */
    public boolean inCell(int area, double x, double z) {
        if (!shared()) {
            return true;
        }
        Vector3i origin = origin(area);
        double half = spacing / 2.0;
        boolean inStrip = Math.abs(x - (origin.x + 0.5)) <= half;
        return generated() ? inStrip : inStrip && Math.abs(z - (origin.z + 0.5)) <= half;
    }

    /** The low corner of the box an area's clear looks in: its cell, from far below its anchor to far above. */
    @Nonnull
    Vector3d cellMin(int area) {
        Vector3i origin = origin(area);
        double half = spacing / 2.0;
        return new Vector3d(origin.x + 0.5 - half, origin.y - CLEAR_REACH, origin.z + 0.5 - half);
    }

    /** The high corner of the box an area's clear looks in. */
    @Nonnull
    Vector3d cellMax(int area) {
        Vector3i origin = origin(area);
        double half = spacing / 2.0;
        return new Vector3d(origin.x + 0.5 + half, origin.y + CLEAR_REACH, origin.z + 0.5 + half);
    }

    /** Is the spot inside {@code area}'s clear box? */
    boolean inClearBox(int area, @Nullable Vector3d at) {
        if (at == null) {
            return false;
        }
        Vector3d min = cellMin(area);
        Vector3d max = cellMax(area);
        return at.x >= min.x && at.x <= max.x && at.y >= min.y && at.y <= max.y && at.z >= min.z && at.z <= max.z;
    }
}
