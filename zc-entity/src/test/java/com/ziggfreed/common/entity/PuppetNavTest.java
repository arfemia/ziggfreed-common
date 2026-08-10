package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for the PURE bounded-A* {@link PuppetNav#search} over a fixture
 * {@link PuppetNav.Walkability} grid (no live server): reachable straight line, blocked/unreachable,
 * step-up (+1), max-drop (2 ok / 3 rejected), radius cap, a wall forcing a detour, and the
 * start-on-goal degenerate case. The live {@link PuppetNav#solve} path (which wraps
 * {@code CollisionModule}) has no unit coverage, matching this ecosystem's precedent for
 * live-store-touching surfaces.
 */
class PuppetNavTest {

    /** A fixture walkability grid: a set of standable feet cells + an optional set of blocked edges. */
    private static final class FixtureGrid implements PuppetNav.Walkability {
        private final Set<String> standable = new HashSet<>();
        private final Set<String> blockedEdges = new HashSet<>();

        FixtureGrid stand(int x, int y, int z) {
            standable.add(cell(x, y, z));
            return this;
        }

        /** A standable row along X at a fixed y/z: {@code [x0, x1]} inclusive. */
        FixtureGrid row(int x0, int x1, int y, int z) {
            for (int x = x0; x <= x1; x++) {
                stand(x, y, z);
            }
            return this;
        }

        /** Block traversal BOTH ways between two adjacent cells (a wall). */
        FixtureGrid wall(int ax, int ay, int az, int bx, int by, int bz) {
            blockedEdges.add(edge(ax, ay, az, bx, by, bz));
            blockedEdges.add(edge(bx, by, bz, ax, ay, az));
            return this;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return standable.contains(cell(x, y, z));
        }

        @Override
        public boolean traversable(int fx, int fy, int fz, int tx, int ty, int tz) {
            return !blockedEdges.contains(edge(fx, fy, fz, tx, ty, tz));
        }

        private static String cell(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        private static String edge(int ax, int ay, int az, int bx, int by, int bz) {
            return cell(ax, ay, az) + ">" + cell(bx, by, bz);
        }
    }

    @Test
    void straightLine_reachable_returnsFullPath() {
        FixtureGrid grid = new FixtureGrid().row(0, 5, 64, 0);
        List<int[]> path = PuppetNav.search(grid, 0, 64, 0, 5, 0, 16, 4096);

        assertNotNull(path, "a clear straight line is reachable");
        assertArrayEquals3(new int[] {0, 64, 0}, path.get(0), "path starts at the start cell");
        int[] last = path.get(path.size() - 1);
        assertEquals(5, last[0], "path ends at the goal column X");
        assertEquals(0, last[2], "path ends at the goal column Z");
        assertEquals(6, path.size(), "shortest path along a straight row is 6 cells (0..5)");
    }

    @Test
    void unreachableGoal_returnsNull() {
        // Only the start neighbourhood is standable; the goal column has no ground anywhere.
        FixtureGrid grid = new FixtureGrid().row(0, 2, 64, 0);
        assertNull(PuppetNav.search(grid, 0, 64, 0, 10, 0, 16, 4096),
                "a goal column with no reachable ground is unreachable");
    }

    @Test
    void stepUp_singleBlock_isTraversed() {
        // Flat to x=2 at y=64, then x=3,4 up one block at y=65.
        FixtureGrid grid = new FixtureGrid().row(0, 2, 64, 0).stand(3, 65, 0).stand(4, 65, 0);
        List<int[]> path = PuppetNav.search(grid, 0, 64, 0, 4, 0, 16, 4096);

        assertNotNull(path, "a 1-block step-up is walkable");
        int[] last = path.get(path.size() - 1);
        assertEquals(4, last[0]);
        assertEquals(65, last[1], "arrives on the raised block (stepped up +1)");
    }

    @Test
    void drop_ofTwo_isTraversed_butThreeIsNot() {
        FixtureGrid dropTwo = new FixtureGrid().row(0, 2, 64, 0).stand(3, 62, 0);
        List<int[]> okPath = PuppetNav.search(dropTwo, 0, 64, 0, 3, 0, 16, 4096);
        assertNotNull(okPath, "a drop of 2 is within MAX_DROP");
        assertEquals(62, okPath.get(okPath.size() - 1)[1], "arrives 2 blocks lower");

        FixtureGrid dropThree = new FixtureGrid().row(0, 2, 64, 0).stand(3, 61, 0);
        assertNull(PuppetNav.search(dropThree, 0, 64, 0, 3, 0, 16, 4096),
                "a drop of 3 exceeds MAX_DROP and is unreachable");
    }

    @Test
    void radiusCap_blocksAnOtherwiseReachableGoal() {
        FixtureGrid grid = new FixtureGrid().row(0, 10, 64, 0);
        assertNotNull(PuppetNav.search(grid, 0, 64, 0, 8, 0, 16, 4096), "reachable with a generous radius");
        assertNull(PuppetNav.search(grid, 0, 64, 0, 8, 0, 3, 4096),
                "the same goal is unreachable once the search radius is tightened below its distance");
    }

    @Test
    void wall_forcesADetour() {
        // A 3x3 pad; a wall on the direct east edge from (0,0) forces the path around via z.
        FixtureGrid grid = new FixtureGrid()
                .row(0, 2, 64, 0)
                .row(0, 2, 64, 1)
                .wall(0, 64, 0, 1, 64, 0);
        List<int[]> path = PuppetNav.search(grid, 0, 64, 0, 2, 0, 16, 4096);

        assertNotNull(path, "the goal is still reachable around the wall");
        int[] last = path.get(path.size() - 1);
        assertEquals(2, last[0]);
        assertEquals(0, last[2]);
        // Direct path would be 3 cells; the detour around the wall is strictly longer.
        assertTrue(path.size() > 3, "the blocked direct edge forces a longer detour path");
    }

    @Test
    void startOnGoalColumn_returnsSingletonPath() {
        FixtureGrid grid = new FixtureGrid().stand(4, 64, 2);
        List<int[]> path = PuppetNav.search(grid, 4, 64, 2, 4, 2, 16, 4096);
        assertNotNull(path);
        assertEquals(1, path.size(), "start already on the goal column is a single-cell path");
        assertArrayEquals3(new int[] {4, 64, 2}, path.get(0), "the single cell is the start");
    }

    @Test
    void nodeBudget_exhaustion_returnsNull() {
        FixtureGrid grid = new FixtureGrid().row(0, 10, 64, 0);
        assertNull(PuppetNav.search(grid, 0, 64, 0, 10, 0, 16, 2),
                "a tiny node budget abandons the search");
    }

    // ==================== groundY column probe ====================

    @Test
    void groundY_prefersHigherStandableWithinWindow() {
        // Both y=65 (step up) and y=63 (drop) standable; the +1 step-up wins.
        FixtureGrid grid = new FixtureGrid().stand(5, 65, 0).stand(5, 63, 0);
        assertEquals(65, PuppetNav.groundY(grid, 5, 0, 64));
    }

    @Test
    void groundY_returnsNoGround_whenColumnEmptyInWindow() {
        FixtureGrid grid = new FixtureGrid().stand(5, 60, 0); // below the drop window from y=64
        assertEquals(PuppetNav.NO_GROUND, PuppetNav.groundY(grid, 5, 0, 64));
    }

    @Test
    void defaultBudget_isPositiveAndBounded() {
        assertTrue(PuppetNav.defaultBudget(16) > 0);
        assertTrue(PuppetNav.defaultBudget(1000) <= 20_000, "budget is capped");
        assertTrue(PuppetNav.defaultBudget(0) > 0, "a zero radius still yields a usable budget");
    }

    private static void assertArrayEquals3(int[] expected, int[] actual, String msg) {
        assertEquals(expected[0], actual[0], msg + " (x)");
        assertEquals(expected[1], actual[1], msg + " (y)");
        assertEquals(expected[2], actual[2], msg + " (z)");
    }
}
