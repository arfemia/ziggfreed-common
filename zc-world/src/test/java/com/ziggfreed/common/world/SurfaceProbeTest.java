package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

/**
 * The surface probe's own rules, driven through its package-private scan and verdicts, since a unit
 * JVM holds no chunk sections and no block types: the first surface from the top wins; air, a cell whose
 * section is not in memory, a transparent block and a skipped key are passed; the standable Y is one
 * above the surface; and a column with no surface answers the caller's fallback as given. The live read
 * is the section cursor's ({@link SectionBlockCursor}), proved by its own test, the linkage check on both
 * server jars and the in-game smoke.
 */
class SurfaceProbeTest {

    @Test
    void theFirstSurfaceFromTheTopWins() {
        assertEquals(70, SurfaceProbe.topSurfaceY(y -> y == 70 || y == 40, 200, -1));
    }

    @Test
    void aColumnWithNoSurfaceAnswersTheFallback() {
        assertEquals(-1, SurfaceProbe.topSurfaceY(y -> false, 200, -1));
    }

    @Test
    void theScanStartsAtScanTopAndNeverReadsRowZero() {
        assertEquals(200, SurfaceProbe.topSurfaceY(y -> y >= 200, 200, -1), "scanTop itself is read");
        assertEquals(-1, SurfaceProbe.topSurfaceY(y -> y > 200 || y == 0, 200, -1),
                "nothing above scanTop is read, and row 0 never is");
    }

    @Test
    void transparentBlocksAndSkippedKeysArePassed_everyOtherBlockIsTheSurface() {
        Set<String> foliage = Set.of("Plant_Leaves_Oak", "Wood_Oak_Trunk");
        assertFalse(SurfaceProbe.isSurface(Opacity.Transparent, "Glass_Clear", null), "a transparent block is seen through");
        assertFalse(SurfaceProbe.isSurface(Opacity.Solid, "Wood_Oak_Trunk", foliage), "a skipped key is passed");
        assertTrue(SurfaceProbe.isSurface(Opacity.Solid, "Rock_Stone", foliage));
        assertTrue(SurfaceProbe.isSurface(Opacity.Cutout, "Plant_Leaves_Oak", null), "only a skip key passes a cutout block");
        assertTrue(SurfaceProbe.isSurface(Opacity.Semitransparent, "Rock_Ice", null));
        assertTrue(SurfaceProbe.isSurface(Opacity.Solid, "Rock_Stone", Set.of()), "an empty skip set skips nothing");
    }

    @Test
    void anEmptyCellIsNeverTheSurface_airAndASectionNotInMemoryAlike() {
        // The cursor reads EMPTY_ID for both; the verdict answers before any block type is looked up.
        assertFalse(SurfaceProbe.isSurfaceBlock(BlockType.EMPTY_ID, null));
        assertFalse(SurfaceProbe.isSurfaceBlock(BlockType.EMPTY_ID, Set.of("Rock_Stone")));
    }

    @Test
    void aStandableYIsOneAboveTheSurface_andTheFallbackIsReturnedAsGiven() {
        assertEquals(71, SurfaceProbe.standableOver(70, 64));
        assertEquals(64, SurfaceProbe.standableOver(Integer.MIN_VALUE, 64), "the fallback is not raised by one");
    }

    @Test
    void aColumnNotInMemoryStandsAtTheFallback() {
        // Every Y of an unloaded column reads EMPTY_ID: the scan finds nothing and standableY keeps the fallback.
        int top = SurfaceProbe.topSurfaceY(y -> SurfaceProbe.isSurfaceBlock(BlockType.EMPTY_ID, null),
                SurfaceProbe.DEFAULT_SCAN_TOP, Integer.MIN_VALUE);
        assertEquals(64, SurfaceProbe.standableOver(top, 64));
    }
}
