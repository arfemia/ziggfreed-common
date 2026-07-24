package com.ziggfreed.common.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.modules.collision.CollisionModule;
import com.hypixel.hytale.server.core.modules.collision.CollisionResult;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A tiny BOUNDED A* grid-walker for pathing a bare-{@link com.hypixel.hytale.component.Holder Holder}
 * puppet (see {@link PlayerPuppetService}) between two nearby stand-spots, WITHOUT the full NPC
 * Role/steering framework. This is the "walkability-predicate + own A*" route the source research
 * crowned over the (dead-end) attempt to reuse the engine's own {@code AStarBase}/
 * {@code MotionControllerWalk} solver: those are hard-coupled to {@code NPCEntity}/{@code Role}
 * (an ~50-method {@code MotionController} interface + Role asset-builder pipeline) and would be MORE
 * work than a hand-rolled bounded search for the identical walkability truth. The walkability truth
 * underneath IS separable and engine-native: {@link CollisionModule} (a plain {@code JavaPlugin}
 * singleton, zero {@code NPCEntity}/{@code Role} coupling).
 *
 * <p><b>Two-layer split (the point of this class).</b> The SEARCH is a pure, engine-free bounded A*
 * over a {@link Walkability} SEAM ({@link #search}) - unit-testable over a fixture grid with no live
 * server. The LIVE entry {@link #solve} wires that search onto {@link CollisionModule}: stand-spots
 * via the instance {@code validatePosition(World, Box, pos, CollisionResult)} (an {@code ON_GROUND}
 * standing test) and swept inter-node moves via the static
 * {@code findCollisions(Box, pos, movement, CollisionResult, accessor)} (a block-collision sweep).
 * A consumer drives the returned waypoints through {@link PlayerPuppetService#walkTick}.
 *
 * <p><b>Search shape.</b> A* over a 1-block grid: 4-connected cardinal horizontal steps, each step
 * resolving its own destination Y by probing the column from {@link #STEP_UP} above the current feet
 * down to {@link #MAX_DROP} below (first standable wins), so a step-up of 1 and a drop of up to 2 are
 * traversed in a single move. Cost is uniform (1 per step); the heuristic is horizontal Manhattan
 * distance (admissible - each move covers exactly one horizontal block, vertical change is "free"
 * within the step/drop window). The search is DOUBLY bounded: a {@code maxRadius} box around the
 * start (nodes outside it are never expanded) and a hard {@code nodeBudget} on expansions, so a
 * short station-to-station path is trivially cheap and an unreachable goal fails fast (returns
 * {@code null}) instead of scanning the world. A* (not greedy best-first) is deliberate: at the
 * &lt;=16-block scale the extra open-set bookkeeping is negligible, and A* returns the genuinely
 * shortest path where greedy would risk an ugly detour for zero real speed win.
 *
 * <p><b>Goal is a COLUMN.</b> The goal is an {@code (x, z)} column, not an exact {@code (x, y, z)}
 * node: the search arrives at the first standable ground height it can reach at that column under the
 * step/drop rules. This matches a station-walk intent ("get to that spot on roughly-flat ground")
 * and is robust to the caller not knowing the exact terrain height; a caller needing a specific
 * elevation on a multi-level column is out of this primitive's narrow scope.
 *
 * <p><b>Threading.</b> {@link #search} is pure (thread-safe, no engine touch). {@link #solve} is
 * WORLD-THREAD ONLY (every {@link CollisionModule} chunk/collision read is, like all chunk access in
 * this codebase); it is fully try-guarded so a bad world/accessor degrades to {@code null}
 * ("no path this time"), never a throw into the caller.
 */
public final class PuppetNav {

    /** Max blocks a single step may ASCEND (step-up height). */
    public static final int STEP_UP = 1;

    /** Max blocks a single step may DESCEND (drop height). */
    public static final int MAX_DROP = 2;

    /** Default search radius (blocks from the start) when a caller does not specify one. */
    public static final int DEFAULT_MAX_RADIUS = 16;

    /** Default puppet collider footprint: a player-ish 0.6 x 1.8 x 0.6 box, feet at the box origin. */
    public static final double DEFAULT_WIDTH = 0.6;
    public static final double DEFAULT_HEIGHT = 1.8;
    public static final double DEFAULT_DEPTH = 0.6;

    /** Sentinel Y returned by {@link #groundY} / column probes when no standable Y exists. */
    public static final int NO_GROUND = Integer.MIN_VALUE;

    private PuppetNav() {
    }

    // ==================== the walkability seam (unit-test fixture point) ====================

    /**
     * The pure walkability oracle the A* {@link #search} runs on - the SEAM that isolates the search
     * math from any live server. The live implementation ({@link #solve}) wraps {@link CollisionModule};
     * a unit test supplies a fixture grid. All coordinates are integer FEET block positions (the block
     * an entity's feet rest on top of).
     */
    public interface Walkability {

        /**
         * Whether the puppet collider can STAND at feet block {@code (x, y, z)}: its body fits (no
         * overlap with solid blocks) AND it rests on solid ground (a supporting block just below).
         */
        boolean standable(int x, int y, int z);

        /**
         * Whether the puppet can MOVE unobstructed from one stand-spot to an adjacent one (a swept
         * block-collision test between the two feet positions). Both endpoints are assumed already
         * {@link #standable}; this rules out a wall/overhang between them.
         */
        boolean traversable(int fromX, int fromY, int fromZ, int toX, int toY, int toZ);
    }

    // ==================== pure bounded A* (engine-free, unit-tested) ====================

    /**
     * Pure bounded A* from a start stand-spot to a goal COLUMN over a {@link Walkability} seam.
     *
     * @param walk       the walkability oracle (fixture in tests, {@link CollisionModule} live).
     * @param fromX      start feet block X.
     * @param fromY      start feet block Y.
     * @param fromZ      start feet block Z.
     * @param goalX      goal column X.
     * @param goalZ      goal column Z.
     * @param maxRadius  search bound: nodes further than this many blocks (per horizontal axis) from
     *                   the start are never expanded ({@code <= 0} degrades to a start-only search).
     * @param nodeBudget hard cap on node expansions; exceeding it abandons the search.
     * @return the waypoint path (feet block cells) from start (inclusive) to the reached goal-column
     *         node (inclusive), or {@code null} if the goal column is unreachable within the bounds.
     *         A start already ON the goal column returns a single-element {@code [start]} path.
     */
    @Nullable
    public static List<int[]> search(@Nonnull Walkability walk,
            int fromX, int fromY, int fromZ,
            int goalX, int goalZ,
            int maxRadius, int nodeBudget) {

        Cell start = new Cell(fromX, fromY, fromZ);
        if (fromX == goalX && fromZ == goalZ) {
            return singleton(start);
        }

        // A* open set ordered by f = g + h; ties broken by lower h (closer to goal expands first).
        Map<Cell, Integer> gScore = new HashMap<>();
        Map<Cell, Cell> cameFrom = new HashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>();

        gScore.put(start, 0);
        open.add(new Open(start, heuristic(fromX, fromZ, goalX, goalZ)));

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int expansions = 0;

        while (!open.isEmpty()) {
            if (++expansions > nodeBudget) {
                return null;
            }
            Open current = open.poll();
            Cell c = current.cell;

            // A lazily-outdated queue entry (a better path to this cell was already popped): skip it.
            Integer bestG = gScore.get(c);
            if (bestG == null || current.g > bestG) {
                continue;
            }

            if (c.x == goalX && c.z == goalZ) {
                return reconstruct(cameFrom, c);
            }

            for (int[] d : dirs) {
                int nx = c.x + d[0];
                int nz = c.z + d[1];

                // maxRadius bound: a box around the START, per horizontal axis.
                if (Math.abs(nx - fromX) > maxRadius || Math.abs(nz - fromZ) > maxRadius) {
                    continue;
                }

                int ny = groundY(walk, nx, nz, c.y);
                if (ny == NO_GROUND) {
                    continue;
                }
                if (!walk.traversable(c.x, c.y, c.z, nx, ny, nz)) {
                    continue;
                }

                Cell next = new Cell(nx, ny, nz);
                int tentativeG = current.g + 1;
                Integer known = gScore.get(next);
                if (known == null || tentativeG < known) {
                    gScore.put(next, tentativeG);
                    cameFrom.put(next, c);
                    open.add(new Open(next, tentativeG, tentativeG + heuristic(nx, nz, goalX, goalZ)));
                }
            }
        }
        return null;
    }

    /**
     * Pure: the standable feet Y for a horizontal neighbour column {@code (x, z)} approached from
     * {@code fromY}, probing from {@link #STEP_UP} above {@code fromY} down to {@link #MAX_DROP} below
     * it and returning the FIRST standable Y (highest wins, so level walking is preferred over a
     * drop), or {@link #NO_GROUND} when the column offers no reachable stand-spot in that window.
     */
    public static int groundY(@Nonnull Walkability walk, int x, int z, int fromY) {
        for (int ny = fromY + STEP_UP; ny >= fromY - MAX_DROP; ny--) {
            if (walk.standable(x, ny, z)) {
                return ny;
            }
        }
        return NO_GROUND;
    }

    private static int heuristic(int x, int z, int goalX, int goalZ) {
        return Math.abs(x - goalX) + Math.abs(z - goalZ);
    }

    @Nonnull
    private static List<int[]> reconstruct(@Nonnull Map<Cell, Cell> cameFrom, @Nonnull Cell goal) {
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        Cell c = goal;
        while (c != null) {
            stack.push(new int[] {c.x, c.y, c.z});
            c = cameFrom.get(c);
        }
        return new ArrayList<>(stack);
    }

    @Nonnull
    private static List<int[]> singleton(@Nonnull Cell c) {
        List<int[]> path = new ArrayList<>(1);
        path.add(new int[] {c.x, c.y, c.z});
        return path;
    }

    // ==================== live entry: wire the search onto CollisionModule ====================

    /**
     * Solve a walk path from {@code from} to {@code to} over the live world, using the default
     * player-ish collider ({@link #DEFAULT_WIDTH} x {@link #DEFAULT_HEIGHT} x {@link #DEFAULT_DEPTH})
     * and {@code maxRadius} with a derived node budget. See
     * {@link #solve(World, ComponentAccessor, Box, Vector3d, Vector3d, int, int)}.
     */
    @Nullable
    public static List<Vector3d> solve(@Nonnull World world, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Vector3d from, @Nonnull Vector3d to, int maxRadius) {
        Box collider = Box.horizontallyCentered(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_DEPTH);
        return solve(world, accessor, collider, from, to, maxRadius, defaultBudget(maxRadius));
    }

    /**
     * Solve a walk path from {@code from} to {@code to}, returning block-centred feet-position
     * waypoints (each {@code (x + 0.5, y, z + 0.5)}) from start to goal, or {@code null} if the goal
     * column is unreachable within {@code maxRadius}/{@code nodeBudget} (or the collision module /
     * world is unavailable). WORLD-THREAD ONLY; never throws.
     *
     * @param world      the world to path in.
     * @param accessor   component accessor (the swept-move test reads the world + entity spatial data
     *                   through it); a {@code Store} or {@code CommandBuffer} both satisfy it.
     * @param collider   the puppet's collider box (feet at the box origin, e.g.
     *                   {@link Box#horizontallyCentered}).
     * @param from       start world position (feet); floored to a block.
     * @param to         goal world position; its {@code (x, z)} column is the target.
     * @param maxRadius  search bound in blocks.
     * @param nodeBudget hard expansion cap.
     */
    @Nullable
    public static List<Vector3d> solve(@Nonnull World world, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Box collider, @Nonnull Vector3d from, @Nonnull Vector3d to, int maxRadius, int nodeBudget) {
        try {
            CollisionModule module = CollisionModule.get();
            if (module == null) {
                return null;
            }
            int fromX = (int) Math.floor(from.x);
            int fromZ = (int) Math.floor(from.z);
            int fromFeetY = (int) Math.floor(from.y);
            int goalX = (int) Math.floor(to.x);
            int goalZ = (int) Math.floor(to.z);

            Walkability walk = new CollisionWalkability(module, world, accessor, collider);

            // Snap the start feet Y to the actual stand-spot (defensive: the caller's `from.y` may be
            // a hair off the block top). Probe the same step/drop window around the reported feet Y.
            int startY = groundY(walk, fromX, fromZ, fromFeetY + STEP_UP);
            if (startY == NO_GROUND) {
                startY = fromFeetY;
            }

            List<int[]> grid = search(walk, fromX, startY, fromZ, goalX, goalZ, maxRadius, nodeBudget);
            if (grid == null) {
                return null;
            }
            List<Vector3d> waypoints = new ArrayList<>(grid.size());
            for (int[] cell : grid) {
                waypoints.add(new Vector3d(cell[0] + 0.5, cell[1], cell[2] + 0.5));
            }
            return waypoints;
        } catch (Throwable t) {
            warn("solve failed: " + t.getMessage(), t);
            return null;
        }
    }

    /** A sensible node budget for a given radius: the bounded box area, quadrupled, capped. */
    public static int defaultBudget(int maxRadius) {
        long span = 2L * Math.max(0, maxRadius) + 1;
        long budget = span * span * 4L;
        return (int) Math.min(budget, 20_000L);
    }

    /** The live {@link Walkability} over {@link CollisionModule}; one reused {@link CollisionResult}. */
    private static final class CollisionWalkability implements Walkability {
        @Nonnull
        private final CollisionModule module;
        @Nonnull
        private final World world;
        @Nonnull
        private final ComponentAccessor<EntityStore> accessor;
        @Nonnull
        private final Box collider;
        @Nonnull
        private final CollisionResult result = new CollisionResult();

        CollisionWalkability(@Nonnull CollisionModule module, @Nonnull World world,
                @Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Box collider) {
            this.module = module;
            this.world = world;
            this.accessor = accessor;
            this.collider = collider;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            int code = module.validatePosition(world, collider, new Vector3d(x + 0.5, y, z + 0.5), result);
            // VALIDATE_INVALID (-1) means the body overlaps a solid block; a valid code is a
            // non-negative bitmask of OK/ON_GROUND/TOUCH_CEIL. Check INVALID first: (-1 & ON_GROUND)
            // would spuriously read as "on ground".
            return code >= 0 && (code & CollisionModule.VALIDATE_ON_GROUND) != 0;
        }

        @Override
        public boolean traversable(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
            Vector3d pos = new Vector3d(fromX + 0.5, fromY, fromZ + 0.5);
            Vector3d move = new Vector3d(toX - fromX, toY - fromY, toZ - fromZ);
            CollisionModule.findCollisions(collider, pos, move, result, accessor);
            return result.getBlockCollisionCount() == 0;
        }
    }

    // ==================== A* internals ====================

    /** An immutable grid cell (feet block position); its value equality keys the A* maps. */
    private record Cell(int x, int y, int z) {
    }

    /** An open-set entry: a cell with its known g cost and its priority f. */
    private static final class Open implements Comparable<Open> {
        @Nonnull
        final Cell cell;
        final int g;
        final int f;

        /** Start-node ctor (g = 0, f = h). */
        Open(@Nonnull Cell cell, int f) {
            this(cell, 0, f);
        }

        Open(@Nonnull Cell cell, int g, int f) {
            this.cell = cell;
            this.g = g;
            this.f = f;
        }

        @Override
        public int compareTo(@Nonnull Open o) {
            int byF = Integer.compare(this.f, o.f);
            if (byF != 0) {
                return byF;
            }
            // Tie-break on the lower h (= f - g): expand the node closer to the goal first.
            return Integer.compare(this.f - this.g, o.f - o.g);
        }
    }

    // ==================== logging ====================

    private static void warn(@Nonnull String message, @Nullable Throwable cause) {
        try {
            if (cause != null) {
                ZiggfreedCommonPlugin.LOGGER.atWarning().withCause(cause).log("[ziggfreed-common][puppetnav] " + message);
            } else {
                ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][puppetnav] " + message);
            }
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
