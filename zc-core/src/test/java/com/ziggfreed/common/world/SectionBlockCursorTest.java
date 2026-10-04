package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

/**
 * The section cursor's own logic, driven through its package-private section source, since a unit JVM
 * holds no chunk sections: which section a cell is in (negative coordinates included), how often a
 * section is looked up, and what a missing section or a failed read answers. The engine lookup it wraps
 * is proved by the leg's linkage check against both server jars and by the in-game smoke.
 */
class SectionBlockCursorTest {

    @Test
    void aCellReadsItsSectionsAnswer_atTheWorldCoordinatesAsGiven() {
        List<int[]> reads = new ArrayList<>();
        SectionBlockCursor cursor = new SectionBlockCursor((x, y, z) -> (bx, by, bz) -> {
            reads.add(new int[] {bx, by, bz});
            return 42;
        });

        assertEquals(42, cursor.blockId(-33, 70, 5));
        assertArrayEquals(new int[] {-33, 70, 5}, reads.get(0), "the section masks world coordinates itself");
    }

    @Test
    void aCellWhoseSectionIsNotInMemoryReadsEmpty() {
        SectionBlockCursor cursor = new SectionBlockCursor((x, y, z) -> null);

        assertEquals(BlockType.EMPTY_ID, cursor.blockId(10, 64, 10));
    }

    @Test
    void oneLookupServesEveryCellOfASection_loadedOrNot() {
        SectionBlockCursor.Section stone = (bx, by, bz) -> 7;
        for (boolean loaded : new boolean[] {true, false}) {
            List<int[]> lookups = new ArrayList<>();
            SectionBlockCursor cursor = new SectionBlockCursor((x, y, z) -> {
                lookups.add(new int[] {x, y, z});
                return loaded ? stone : null;
            });
            for (int x = 0; x < 32; x++) {
                cursor.blockId(x, 64, 0);
            }
            assertEquals(1, lookups.size(), "32 cells of one section take one lookup (loaded: " + loaded + ")");
            cursor.blockId(32, 64, 0);
            assertEquals(2, lookups.size(), "the next section is looked up once the walk reaches it");
        }
    }

    @Test
    void negativeCoordinatesAndSectionEdgesKeyTheSectionTheEngineResolves() {
        List<int[]> lookups = new ArrayList<>();
        SectionBlockCursor cursor = new SectionBlockCursor((x, y, z) -> {
            lookups.add(new int[] {x, y, z});
            return (bx, by, bz) -> 7;
        });

        cursor.blockId(-32, 64, 0);
        cursor.blockId(-1, 95, 31);
        assertEquals(1, lookups.size(), "x -32..-1, y 64..95 and z 0..31 are one section");
        cursor.blockId(0, 95, 31);
        assertEquals(2, lookups.size(), "x = 0 is not in the section x = -1 is in");
        cursor.blockId(0, 95, -1);
        assertEquals(3, lookups.size(), "z = -1 is not in the section z = 0 is in");
        cursor.blockId(0, 96, -1);
        assertEquals(4, lookups.size(), "y = 96 is not in the section y = 95 is in");
        cursor.blockId(-33, 96, -1);
        assertEquals(5, lookups.size(), "x = -33 is not in the section x = -32 is in");
    }

    @Test
    void aLookupThatThrowsReadsEmptyAndTheNextReadTriesAgain() {
        int[] calls = {0};
        SectionBlockCursor cursor = new SectionBlockCursor((x, y, z) -> {
            if (calls[0]++ == 0) {
                throw new IllegalStateException("section map busy");
            }
            return (bx, by, bz) -> 7;
        });

        assertEquals(BlockType.EMPTY_ID, cursor.blockId(5, 64, 5));
        assertEquals(7, cursor.blockId(5, 64, 5), "the failed read cached nothing");
        assertEquals(2, calls[0]);
    }

    @Test
    void aReadThatDoesNotLinkReadsEmptyToo() {
        // The failure class Update 7 caused: an engine member missing from this server build.
        int[] calls = {0};
        SectionBlockCursor cursor = new SectionBlockCursor((x, y, z) -> {
            calls[0]++;
            throw new NoSuchMethodError("ChunkStore.getChunkSectionReferenceAtBlock");
        });

        assertEquals(BlockType.EMPTY_ID, cursor.blockId(5, 64, 5));
        assertEquals(BlockType.EMPTY_ID, cursor.blockId(5, 64, 5));
        assertEquals(2, calls[0], "nothing is cached after a failed read");
    }

    @Test
    void aSectionWhoseReadThrowsReadsEmptyAndIsLookedUpAgain() {
        List<int[]> lookups = new ArrayList<>();
        SectionBlockCursor cursor = new SectionBlockCursor((x, y, z) -> {
            lookups.add(new int[] {x, y, z});
            return (bx, by, bz) -> {
                throw new IllegalStateException("component gone");
            };
        });

        assertEquals(BlockType.EMPTY_ID, cursor.blockId(5, 64, 5));
        assertEquals(BlockType.EMPTY_ID, cursor.blockId(5, 64, 5));
        assertEquals(2, lookups.size(), "a section whose read failed is not trusted again");
    }

    @Test
    void aWorldThatCannotAnswerGivesACursorThatReadsEmpty() {
        // No world at all stands in for a chunk store that cannot be read: the cursor still reads, empty.
        SectionBlockCursor cursor = SectionBlockCursor.of(null);

        assertEquals(BlockType.EMPTY_ID, cursor.blockId(0, 64, 0));
        assertEquals(BlockType.EMPTY_ID, cursor.blockId(-207, 122, 47));
    }
}
