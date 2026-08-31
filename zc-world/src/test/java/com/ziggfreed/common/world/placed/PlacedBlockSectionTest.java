package com.ziggfreed.common.world.placed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.math.util.ChunkUtil;

/**
 * The per-block record itself: which blocks in one chunk section a player put there.
 *
 * <p>This is where the guard's decision actually lives, so it is pinned here rather than through
 * the ledger. Reaching it through {@link PlacedBlockLedger} would need a live world, a loaded chunk
 * and a registered chunk-store component - the ECS plumbing that lands in in-game smoke, as with
 * every other chunk-store component. What a unit test CAN pin is everything that decides an answer:
 * which bit a position maps to, that spending a mark clears it, that the array is only allocated
 * once something is marked and released again when nothing is, and that a section survives being
 * written out and read back, which is what a chunk save and load does to it.
 */
class PlacedBlockSectionTest {

    /** Serialize and deserialize are private, being the codec's own two ends. */
    private static PlacedBlockSection roundTrip(PlacedBlockSection original) throws Exception {
        Method serialize = PlacedBlockSection.class
                .getDeclaredMethod("serialize", com.hypixel.hytale.codec.ExtraInfo.class);
        serialize.setAccessible(true);
        byte[] bytes = (byte[]) serialize.invoke(original, (Object) null);

        PlacedBlockSection restored = new PlacedBlockSection();
        Method deserialize = PlacedBlockSection.class
                .getDeclaredMethod("deserialize", byte[].class, com.hypixel.hytale.codec.ExtraInfo.class);
        deserialize.setAccessible(true);
        deserialize.invoke(restored, bytes, null);
        return restored;
    }

    @Test
    void aBlockNobodyMarkedIsNotPlaced() {
        PlacedBlockSection section = new PlacedBlockSection();
        assertFalse(section.isMarked(ChunkUtil.indexBlock(3, 4, 5)));
        assertEquals(0, section.markedCount());
    }

    @Test
    void aMarkedBlockReadsAsPlaced() {
        PlacedBlockSection section = new PlacedBlockSection();
        int index = ChunkUtil.indexBlock(3, 4, 5);
        assertTrue(section.setMarked(index, true), "marking an unmarked block changes it");
        assertTrue(section.isMarked(index));
        assertEquals(1, section.markedCount());
    }

    @Test
    void markingTwiceChangesNothingTheSecondTime() {
        PlacedBlockSection section = new PlacedBlockSection();
        int index = ChunkUtil.indexBlock(9, 9, 9);
        assertTrue(section.setMarked(index, true));
        assertFalse(section.setMarked(index, true), "the block was already marked");
        assertEquals(1, section.markedCount(), "and it is still one block, not two");
    }

    @Test
    void clearingAMarkTakesItAway() {
        PlacedBlockSection section = new PlacedBlockSection();
        int index = ChunkUtil.indexBlock(1, 2, 3);
        section.setMarked(index, true);
        assertTrue(section.setMarked(index, false));
        assertFalse(section.isMarked(index), "the block is ordinary again once its mark is spent");
        assertEquals(0, section.markedCount());
    }

    @Test
    void oneBlocksMarkDoesNotLeakOntoItsNeighbours() {
        PlacedBlockSection section = new PlacedBlockSection();
        section.setMarked(ChunkUtil.indexBlock(8, 8, 8), true);
        assertTrue(section.isMarked(ChunkUtil.indexBlock(8, 8, 8)));
        assertFalse(section.isMarked(ChunkUtil.indexBlock(9, 8, 8)));
        assertFalse(section.isMarked(ChunkUtil.indexBlock(8, 9, 8)));
        assertFalse(section.isMarked(ChunkUtil.indexBlock(8, 8, 9)));
        assertFalse(section.isMarked(ChunkUtil.indexBlock(7, 8, 8)));
    }

    /**
     * Every block in a section has its own bit, including the two ends: an off-by-one in the
     * packing would have one position answering for another, which is either a free block or a
     * refused one depending on which way it went.
     */
    @Test
    void everyBlockInASectionHasItsOwnBit() {
        PlacedBlockSection section = new PlacedBlockSection();
        for (int index = 0; index < ChunkUtil.SIZE_BLOCKS; index++) {
            assertTrue(section.setMarked(index, true), "index " + index + " must be its own bit");
        }
        assertEquals(ChunkUtil.SIZE_BLOCKS, section.markedCount(),
                "every block in the section is marked, and none of them shares a bit");
        for (int index = 0; index < ChunkUtil.SIZE_BLOCKS; index++) {
            assertTrue(section.isMarked(index));
        }
    }

    @Test
    void aSectionThatHoldsNothingCarriesNoArray() throws Exception {
        PlacedBlockSection empty = new PlacedBlockSection();
        Method serialize = PlacedBlockSection.class
                .getDeclaredMethod("serialize", com.hypixel.hytale.codec.ExtraInfo.class);
        serialize.setAccessible(true);
        byte[] bytes = (byte[]) serialize.invoke(empty, (Object) null);
        assertEquals(1, bytes.length, "an unmarked section costs one flag byte, not a whole array");

        PlacedBlockSection emptied = new PlacedBlockSection();
        int index = ChunkUtil.indexBlock(2, 2, 2);
        emptied.setMarked(index, true);
        emptied.setMarked(index, false);
        byte[] afterwards = (byte[]) serialize.invoke(emptied, (Object) null);
        assertEquals(1, afterwards.length,
                "and it goes back to costing one byte once its last mark is spent");
    }

    @Test
    void marksSurviveBeingWrittenOutAndReadBack() throws Exception {
        PlacedBlockSection section = new PlacedBlockSection();
        int marked = ChunkUtil.indexBlock(5, 6, 7);
        int alsoMarked = ChunkUtil.indexBlock(31, 31, 31);
        int untouched = ChunkUtil.indexBlock(5, 6, 8);
        section.setMarked(marked, true);
        section.setMarked(alsoMarked, true);

        PlacedBlockSection restored = roundTrip(section);

        assertTrue(restored.isMarked(marked), "a placement is still a placement after a chunk load");
        assertTrue(restored.isMarked(alsoMarked));
        assertFalse(restored.isMarked(untouched));
        assertEquals(2, restored.markedCount());
    }

    @Test
    void anEmptySectionSurvivesBeingWrittenOutAndReadBack() throws Exception {
        PlacedBlockSection restored = roundTrip(new PlacedBlockSection());
        assertEquals(0, restored.markedCount());
        assertFalse(restored.isMarked(ChunkUtil.indexBlock(0, 0, 0)));
    }

    @Test
    void aCloneCarriesTheMarksAndNotTheArray() {
        PlacedBlockSection section = new PlacedBlockSection();
        int index = ChunkUtil.indexBlock(4, 4, 4);
        section.setMarked(index, true);

        PlacedBlockSection copy = (PlacedBlockSection) section.clone();
        assertNotSame(section, copy);
        assertTrue(copy.isMarked(index));
        assertEquals(1, copy.markedCount());

        copy.setMarked(ChunkUtil.indexBlock(6, 6, 6), true);
        assertFalse(section.isMarked(ChunkUtil.indexBlock(6, 6, 6)),
                "writing to the copy must not reach back into the original");
    }

    /**
     * With no registered component type - a boot where registration failed - every read has to
     * answer "not placed" rather than throw. That pays out for placements, which is the wrong
     * answer, but it is the SAFE wrong answer: the alternative refuses every break on the server.
     */
    @Test
    void withNoRegisteredTypeNothingReadsAsPlaced() {
        PlacedBlockSection.resetTypeForTests();
        assertEquals(null, PlacedBlockSection.type(), "the fixture starts with nothing registered");
    }
}
