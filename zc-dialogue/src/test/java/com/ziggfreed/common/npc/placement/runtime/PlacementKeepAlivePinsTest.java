package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.runtime.PlacementKeepAlivePins.Edge;

/**
 * The chunk-pin claim arithmetic.
 *
 * <p>The engine pin is reference counted with no auto-release, so the ONLY safe pattern is pin on
 * the first claimant and unpin on the last. Getting this wrong does not fail loudly: it leaves a
 * chunk pinned forever, in every world, for the rest of the process.
 */
class PlacementKeepAlivePinsTest {

    private static final long CHUNK_A = 1L;
    private static final long CHUNK_B = 2L;

    @Test
    void theFirstClaimOnAChunkPinsIt() {
        Map<Long, Set<String>> table = PlacementKeepAlivePins.newChunkTable();

        assertEquals(Edge.FIRST, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", true));
    }

    @Test
    void aSecondPlacementInTheSameChunkDoesNotPinAgain() {
        Map<Long, Set<String>> table = PlacementKeepAlivePins.newChunkTable();
        PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", true);

        assertEquals(Edge.NONE, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "shop|worldspawn:0", true),
                "two placements in one chunk must cost exactly one engine pin");
    }

    @Test
    void reClaimingWithTheSameKeyIsIdempotent() {
        Map<Long, Set<String>> table = PlacementKeepAlivePins.newChunkTable();
        PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", true);

        assertEquals(Edge.NONE, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", true),
                "a sweep runs repeatedly over a standing NPC; each pass must not add a pin");
    }

    @Test
    void unpinningHappensOnlyOnTheLastRelease() {
        Map<Long, Set<String>> table = PlacementKeepAlivePins.newChunkTable();
        PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", true);
        PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "shop|worldspawn:0", true);

        assertEquals(Edge.NONE, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", false),
                "releasing one of two claimants must not unpin the chunk out from under the other");
        assertEquals(Edge.LAST, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "shop|worldspawn:0", false));
    }

    @Test
    void aReleaseWithNoMatchingClaimIsANoOp() {
        Map<Long, Set<String>> table = PlacementKeepAlivePins.newChunkTable();

        assertEquals(Edge.NONE, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "nobody", false));
        assertTrue(table.isEmpty());
    }

    @Test
    void anEmptiedChunkDropsOutOfTheTable() {
        Map<Long, Set<String>> table = PlacementKeepAlivePins.newChunkTable();
        PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", true);
        PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", false);

        assertFalse(table.containsKey(CHUNK_A), "an empty holder set must not linger as a leak");
    }

    @Test
    void chunksAreTrackedIndependently() {
        Map<Long, Set<String>> table = PlacementKeepAlivePins.newChunkTable();

        assertEquals(Edge.FIRST, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", true));
        assertEquals(Edge.FIRST, PlacementKeepAlivePins.applyClaim(table, CHUNK_B, "hub|coords:0", true));
        assertEquals(2, table.size());
        assertEquals(Edge.LAST, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", false));
        assertEquals(1, table.size());
    }

    @Test
    void aBalancedPinUnpinCycleLeavesNothingBehind() {
        Map<Long, Set<String>> table = PlacementKeepAlivePins.newChunkTable();
        for (int i = 0; i < 50; i++) {
            // A sweep re-asserting the same claim over and over, which is the real access pattern.
            PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", true);
        }
        assertEquals(Edge.LAST, PlacementKeepAlivePins.applyClaim(table, CHUNK_A, "hub|worldspawn:0", false),
                "fifty sweeps must still release on ONE unpin, or the chunk never unloads again");
        assertTrue(table.isEmpty());
    }
}
