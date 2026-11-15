package com.ziggfreed.common.world.placed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.UUID;

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
     * Who placed a block is read back per block, however many players built in one section: the
     * palette widens from no data a block to packed indices as placers arrive, and an index written
     * before a widening still names the same player after it.
     */
    @Test
    void eachMarkedBlockKeepsItsOwnPlacerAcrossWidening() throws Exception {
        PlacedBlockSection section = new PlacedBlockSection();
        UUID[] players = new UUID[20];
        for (int i = 0; i < players.length; i++) {
            players[i] = new UUID(7L, i);
        }
        section.setMarked(ChunkUtil.indexBlock(0, 0, 0), true, players[0]);
        assertEquals(0, section.placerBits(), "one placer in a section costs nothing a block");
        for (int i = 1; i < players.length; i++) {
            section.setMarked(ChunkUtil.indexBlock(i, 1, 2), true, players[i]);
        }
        assertEquals(8, section.placerBits(), "twenty placers take a byte a block");
        assertEquals(players[0], section.placerAt(ChunkUtil.indexBlock(0, 0, 0)));
        for (int i = 1; i < players.length; i++) {
            assertEquals(players[i], section.placerAt(ChunkUtil.indexBlock(i, 1, 2)), "placer " + i);
        }

        PlacedBlockSection restored = roundTrip(section);
        assertEquals(players[0], restored.placerAt(ChunkUtil.indexBlock(0, 0, 0)),
                "the placers survive a chunk save and load");
        for (int i = 1; i < players.length; i++) {
            assertEquals(players[i], restored.placerAt(ChunkUtil.indexBlock(i, 1, 2)), "placer " + i);
        }
        assertEquals(null, restored.placerAt(ChunkUtil.indexBlock(30, 30, 30)), "an unmarked block has no placer");
    }

    /**
     * A section saved before placers were kept reads its marks as placed by nobody known, and the
     * first player to place there afterwards does not inherit them.
     */
    @Test
    void marksSavedWithoutPlacersStayUnknown() throws Exception {
        PlacedBlockSection legacy = new PlacedBlockSection();
        int old = ChunkUtil.indexBlock(3, 3, 3);
        legacy.setMarked(old, true);
        PlacedBlockSection restored = roundTrip(legacy);
        assertTrue(restored.isMarked(old));
        assertEquals(null, restored.placerAt(old));

        UUID player = new UUID(1L, 2L);
        int fresh = ChunkUtil.indexBlock(4, 4, 4);
        restored.setMarked(fresh, true, player);
        assertEquals(player, restored.placerAt(fresh));
        assertEquals(null, restored.placerAt(old), "an older mark is never handed to a newer placer");
    }

    /**
     * Whether the anti-exploit guard holds a placement is kept per block beside its placer (R879): a placement the
     * guard leaves alone still names its placer, both survive a chunk save, and a mark kept before the guard state
     * was (no palette at all) reads guarded, as every mark was then.
     */
    @Test
    void theGuardStateSurvivesASaveBesideThePlacer() throws Exception {
        PlacedBlockSection section = new PlacedBlockSection();
        UUID builder = new UUID(3L, 4L);
        int guarded = ChunkUtil.indexBlock(1, 1, 1);
        int open = ChunkUtil.indexBlock(2, 1, 1);
        section.setMarked(guarded, true, builder, true);
        section.setMarked(open, true, builder, false);
        assertTrue(section.guardedAt(guarded));
        assertFalse(section.guardedAt(open));
        assertEquals(builder, section.placerAt(open), "a placement the guard leaves alone still names its placer");

        PlacedBlockSection restored = roundTrip(section);
        assertTrue(restored.guardedAt(guarded));
        assertFalse(restored.guardedAt(open));
        assertEquals(builder, restored.placerAt(guarded));
        assertEquals(builder, restored.placerAt(open));

        PlacedBlockSection lone = new PlacedBlockSection();
        lone.setMarked(open, true, builder, false);
        PlacedBlockSection loneRestored = roundTrip(lone);
        assertFalse(loneRestored.guardedAt(open), "a section one exempt builder built in keeps that through a save");
        assertEquals(builder, loneRestored.placerAt(open));
        assertFalse(loneRestored.guardedAt(guarded), "an unmarked block is not guarded");

        PlacedBlockSection legacy = new PlacedBlockSection();
        legacy.setMarked(guarded, true);
        assertTrue(roundTrip(legacy).guardedAt(guarded), "a mark with no placer kept reads guarded");
    }

    /**
     * The spared mark is its own record: it survives a save with what each spared block was, beside
     * the placers, and an emptied section drops it.
     */
    @Test
    void sparedBlocksSurviveBeingWrittenOutAndReadBack() throws Exception {
        PlacedBlockSection section = new PlacedBlockSection();
        int spared = ChunkUtil.indexBlock(9, 10, 11);
        int torch = ChunkUtil.indexBlock(0, 0, 0);
        int wall = ChunkUtil.indexBlock(31, 31, 31);
        assertTrue(section.spare(spared, "Rock_Stone"));
        assertTrue(section.spare(torch, "Wood_Torch"));
        assertTrue(section.spare(wall, "Rock_Stone"));
        assertFalse(section.spare(wall, "Rock_Stone"), "the same block spared again changes nothing");
        assertFalse(section.isMarked(spared), "a spared block need not be a guarded placement");
        UUID player = new UUID(5L, 6L);
        int placed = ChunkUtil.indexBlock(2, 2, 2);
        section.setMarked(placed, true, player);

        PlacedBlockSection restored = roundTrip(section);
        assertTrue(restored.isSpared(spared));
        assertFalse(restored.isSpared(ChunkUtil.indexBlock(9, 10, 12)));
        assertEquals(3, restored.sparedCount());
        assertEquals("Rock_Stone", restored.sparedAs(spared));
        assertEquals("Wood_Torch", restored.sparedAs(torch));
        assertEquals("Rock_Stone", restored.sparedAs(wall));
        assertEquals(null, restored.sparedAs(ChunkUtil.indexBlock(9, 10, 12)));
        assertEquals(player, restored.placerAt(placed), "the placers still read after the spared blocks");

        assertTrue(restored.unspare(spared));
        assertTrue(restored.unspare(torch));
        assertTrue(restored.unspare(wall));
        assertFalse(restored.unspare(wall), "a mark already gone is not forgotten twice");
        assertEquals(0, restored.sparedCount());
        PlacedBlockSection cleared = roundTrip(restored);
        assertFalse(cleared.isSpared(spared));
        assertEquals(player, cleared.placerAt(placed));
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
