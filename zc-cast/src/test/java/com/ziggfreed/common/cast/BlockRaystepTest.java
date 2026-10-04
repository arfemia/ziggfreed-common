package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.function.IntPredicate;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Where a look ray stops, driven through the walk's package-private block-id source, since a unit JVM
 * holds no chunk sections and no block types. A fake world stands in: 0 is what the section read
 * answers for air and for a cell whose section is not in memory, {@code STONE} is solid and
 * {@code LEAVES} is a loaded block that is not. Steps of 0.5 are exact in binary, so the distances
 * below are exact. The live read is {@code SectionBlockCursor}'s, proved by its own test and the
 * leg's linkage check.
 */
class BlockRaystepTest {

    private static final int STONE = 7;
    private static final int LEAVES = 9;

    /** Solid is stone alone; an empty cell must never reach the solid test, as before the port. */
    private static final IntPredicate SOLID = id -> {
        assertNotEquals(0, id, "an empty cell (air, or a section not in memory) never reaches the solid test");
        return id == STONE;
    };

    private static final Vector3d ORIGIN = new Vector3d(0.5, 64.5, 0.5);
    private static final Vector3d EAST = new Vector3d(1, 0, 0);
    private static final Vector3d WEST = new Vector3d(-1, 0, 0);

    /** Stone filling every cell from x = 5 eastward; air elsewhere. */
    private static final BlockRaystep.BlockIds WALL_AT_X5 = (x, y, z) -> x >= 5 ? STONE : 0;

    @Test
    void theFirstSolidBlockStopsTheRay_pulledBackByTheWallPullback() {
        // t = 4.5 is the first sample in x = 5 (0.5 + 4.5 = 5.0).
        assertEquals(4.2, BlockRaystep.clearDistanceAlong(WALL_AT_X5, SOLID, ORIGIN, EAST, 20.0, 0.5, 0.3), 1e-9);
    }

    @Test
    void aRayThatClearsReturnsItsFullLength() {
        assertEquals(20.0, BlockRaystep.clearDistanceAlong((x, y, z) -> 0, SOLID, ORIGIN, EAST, 20.0, 0.5, 0.3), 0.0);
    }

    @Test
    void anEmptyCellIsClear_airAndASectionNotInMemoryAlike() {
        // Cells 1..7 read 0 (air, or a section the read cannot see); the stone beyond still stops the ray.
        BlockRaystep.BlockIds gapThenWall = (x, y, z) -> x >= 8 ? STONE : 0;
        assertEquals(7.5, BlockRaystep.clearDistanceAlong(gapThenWall, SOLID, ORIGIN, EAST, 20.0, 0.5, 0.0), 1e-9);
    }

    @Test
    void aLoadedBlockThatIsNotSolidIsClear() {
        BlockRaystep.BlockIds leavesThenWall = (x, y, z) -> x >= 5 ? STONE : (x >= 2 ? LEAVES : 0);
        assertEquals(4.5, BlockRaystep.clearDistanceAlong(leavesThenWall, SOLID, ORIGIN, EAST, 20.0, 0.5, 0.0), 1e-9);
    }

    @Test
    void aWallAtTheFirstStepNeverPutsTheCasterBehindTheStart() {
        BlockRaystep.BlockIds wallAtX1 = (x, y, z) -> x >= 1 ? STONE : 0;
        // The first sample, t = 0.5, is already in the wall.
        assertEquals(0.2, BlockRaystep.clearDistanceAlong(wallAtX1, SOLID, ORIGIN, EAST, 20.0, 0.5, 0.3), 1e-9);
        assertEquals(0.0, BlockRaystep.clearDistanceAlong(wallAtX1, SOLID, ORIGIN, EAST, 20.0, 0.5, 0.8), 0.0,
                "a pullback longer than the distance travelled clamps at 0");
        assertEquals(0.5, BlockRaystep.clearDistanceAlong(wallAtX1, SOLID, ORIGIN, EAST, 20.0, 0.5, -1.0), 1e-9,
                "a negative pullback counts as none");
    }

    @Test
    void aRayIntoNegativeCoordinatesReadsTheCellItIsIn() {
        // Westward from x = 0.5 the sample at t = 3.0 is x = -2.5, which is cell -3 (floor), not -2.
        BlockRaystep.BlockIds wallAtXMinus3 = (x, y, z) -> x <= -3 ? STONE : 0;
        assertEquals(3.0, BlockRaystep.clearDistanceAlong(wallAtXMinus3, SOLID, ORIGIN, WEST, 20.0, 0.5, 0.0), 1e-9);
    }

    @Test
    void hitPositionIsTheExactSampleInTheWall_orTheRaysEnd() {
        Vector3d hit = BlockRaystep.hitPositionAlong(WALL_AT_X5, SOLID, ORIGIN, EAST, 20.0, 0.5);
        assertEquals(5.0, hit.x, 1e-9);
        assertEquals(64.5, hit.y, 0.0);
        assertEquals(0.5, hit.z, 0.0);

        Vector3d end = BlockRaystep.hitPositionAlong((x, y, z) -> 0, SOLID, ORIGIN, EAST, 20.0, 0.5);
        assertEquals(20.5, end.x, 1e-9);
        assertEquals(64.5, end.y, 0.0);
        assertEquals(0.5, end.z, 0.0);
    }

    @Test
    void withNoWorldTheGuardsAnswerWithoutReadingABlock() {
        assertEquals(10.0, BlockRaystep.clearDistance(null, ORIGIN, EAST, 10.0, 0.3, 0.3), 0.0);
        Vector3d at = BlockRaystep.hitPosition(null, ORIGIN, EAST, 10.0, 0.3);
        assertEquals(ORIGIN.x, at.x, 0.0);
        assertEquals(ORIGIN.y, at.y, 0.0);
        assertEquals(ORIGIN.z, at.z, 0.0);
    }
}
