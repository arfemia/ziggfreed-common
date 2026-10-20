package com.ziggfreed.common.world.gather;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The bounds every gather shape's walk keeps (the flood fill that fells a tree or mines a vein must stop however much
 * of one block it meets), and the player frame an offsets walk turns in. Pure: the cells are a predicate.
 */
class ShapeWalksTest {

    /** A world that is all one block: every cell qualifies. */
    private static final ShapeWalks.Cells EVERYTHING = (x, y, z) -> true;

    @Test
    void aConnectedWalkStopsAtItsCount() {
        List<int[]> cells = ShapeWalks.connected(0, 64, 0, 12, EVERYTHING);
        assertEquals(12, cells.size());
        for (int[] cell : cells) {
            assertFalse(cell[0] == 0 && cell[1] == 64 && cell[2] == 0, "the first block is never answered");
        }
    }

    @Test
    void aConnectedWalkNeverLeavesItsReachNorPassesItsVisitLimit() {
        int[] visits = {0};
        List<int[]> cells = ShapeWalks.connected(0, 64, 0, Integer.MAX_VALUE, (x, y, z) -> {
            visits[0]++;
            return true;
        });
        assertTrue(visits[0] <= ShapeWalks.VISIT_LIMIT, "it looked at " + visits[0] + " cells");
        for (int[] cell : cells) {
            assertTrue(Math.abs(cell[0]) <= ShapeWalks.REACH && Math.abs(cell[1] - 64) <= ShapeWalks.REACH
                    && Math.abs(cell[2]) <= ShapeWalks.REACH);
        }
    }

    @Test
    void aConnectedWalkFollowsOnlyJoinedCells() {
        // A column of logs from y 1 to 5 and a lone log apart from it.
        ShapeWalks.Cells logs = (x, y, z) -> (x == 0 && z == 0 && y >= 1 && y <= 5) || (x == 5 && y == 3 && z == 0);
        List<int[]> cells = ShapeWalks.connected(0, 0, 0, 100, logs);
        assertEquals(5, cells.size(), "the lone log is not joined to the column");
    }

    @Test
    void anAreaWalkKeepsToItsSquareAndLayersNearestFirst() {
        List<int[]> cells = ShapeWalks.area(0, 10, 0, 1, 10, 10, 100, EVERYTHING);
        assertEquals(8, cells.size(), "a 3 by 3 of one layer, the first block left out");
        assertEquals(1, Math.abs(cells.get(0)[0]) + Math.abs(cells.get(0)[2]), "the nearest come first");
        assertEquals(3, ShapeWalks.area(0, 10, 0, 1, 10, 10, 3, EVERYTHING).size(), "held at its count");
    }

    @Test
    void anOffsetsWalkTurnsWithThePlayer() {
        int[][] right = {{0, 0, 1}};
        // Facing north (negative z), the right hand is east (positive x).
        assertArrayEquals(new int[] {1, 0, 0}, ShapeWalks.offsets(0, 0, 0, 0, -1, right, 1, EVERYTHING).get(0));
        // Facing east, the right hand is south (positive z).
        assertArrayEquals(new int[] {0, 0, 1}, ShapeWalks.offsets(0, 0, 0, 1, 0, right, 1, EVERYTHING).get(0));
        int[][] ahead = {{1, -1, 0}};
        assertArrayEquals(new int[] {0, -1, -1}, ShapeWalks.offsets(0, 0, 0, 0, -1, ahead, 1, EVERYTHING).get(0));
    }

    @Test
    void theFacingIsTheLargerFlatAxis() {
        assertArrayEquals(new int[] {1, 0}, ShapeWalks.facing(0.0, 0.0, 3.5, 1.5));
        assertArrayEquals(new int[] {0, -1}, ShapeWalks.facing(0.0, 0.0, 0.5, -4.5));
    }

    @Test
    void aCountIsHeldToTheHardCap() {
        assertEquals(0, ShapeWalks.capped(-1.0));
        assertEquals(0, ShapeWalks.capped(Double.NaN));
        assertEquals(12, ShapeWalks.capped(12.9));
        assertEquals(ShapeWalks.MAX_COUNT, ShapeWalks.capped(10_000.0));
    }
}
