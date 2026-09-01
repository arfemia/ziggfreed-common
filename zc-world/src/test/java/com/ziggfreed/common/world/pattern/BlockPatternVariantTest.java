package com.ziggfreed.common.world.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Variant expansion pinned by hand: an asymmetric three-cell fixture whose eight orientations are
 * all distinct, each transformed offset computed on paper against the documented convention (one
 * positive quarter-turn maps an offset (x, y, z) to (z, y, -x); the X-mirror negates the authored
 * X first, then the turns apply). Payloads are opaque strings; nothing here means anything to the
 * library.
 */
class BlockPatternVariantTest {

    /**
     * The asymmetric fixture: anchor "alpha" at the origin, "beta" one block +X, "gamma" one up
     * and two +Z. No two of its eight orientations share an offset list.
     */
    private static BlockPattern<String> asymmetric(boolean rotate, boolean mirror) {
        return BlockPattern.compile(List.of(
                new PatternCell<>(0, 0, 0, "alpha"),
                new PatternCell<>(1, 0, 0, "beta"),
                new PatternCell<>(0, 1, 2, "gamma")), 0, rotate, mirror);
    }

    private static void assertCell(PatternVariant<String> variant, int cellIndex,
            int dx, int dy, int dz) {
        assertEquals(dx, variant.dx(cellIndex), "dx of cell " + cellIndex);
        assertEquals(dy, variant.dy(cellIndex), "dy of cell " + cellIndex);
        assertEquals(dz, variant.dz(cellIndex), "dz of cell " + cellIndex);
    }

    @Test
    void theFourYawsTransformExactlyAsComputedByHand() {
        List<PatternVariant<String>> variants = asymmetric(true, false).variants();
        assertEquals(4, variants.size());

        // yaw 0: authored offsets untouched.
        assertCell(variants.get(0), 1, 1, 0, 0);
        assertCell(variants.get(0), 2, 0, 1, 2);

        // yaw 1: (x, z) -> (z, -x).
        assertCell(variants.get(1), 1, 0, 0, -1);
        assertCell(variants.get(1), 2, 2, 1, 0);

        // yaw 2: (x, z) -> (-x, -z).
        assertCell(variants.get(2), 1, -1, 0, 0);
        assertCell(variants.get(2), 2, 0, 1, -2);

        // yaw 3: (x, z) -> (-z, x).
        assertCell(variants.get(3), 1, 0, 0, 1);
        assertCell(variants.get(3), 2, -2, 1, 0);
    }

    @Test
    void theMirroredFourNegateXFirstThenTurn() {
        List<PatternVariant<String>> variants = asymmetric(true, true).variants();
        assertEquals(8, variants.size());

        // mirrored yaw 0: beta's +X flips; gamma has no X to flip.
        assertCell(variants.get(4), 1, -1, 0, 0);
        assertCell(variants.get(4), 2, 0, 1, 2);

        // mirrored yaw 1: (-1, 0, 0) -> (0, 0, 1); (0, 1, 2) -> (2, 1, 0).
        assertCell(variants.get(5), 1, 0, 0, 1);
        assertCell(variants.get(5), 2, 2, 1, 0);

        // mirrored yaw 2: (-1, 0, 0) -> (1, 0, 0); (0, 1, 2) -> (0, 1, -2).
        assertCell(variants.get(6), 1, 1, 0, 0);
        assertCell(variants.get(6), 2, 0, 1, -2);

        // mirrored yaw 3: (-1, 0, 0) -> (0, 0, -1); (0, 1, 2) -> (-2, 1, 0).
        assertCell(variants.get(7), 1, 0, 0, -1);
        assertCell(variants.get(7), 2, -2, 1, 0);
    }

    @Test
    void yawQuarterTurnsAndMirroredRoundTripPerVariant() {
        List<PatternVariant<String>> variants = asymmetric(true, true).variants();
        for (int i = 0; i < 8; i++) {
            PatternVariant<String> variant = variants.get(i);
            assertEquals(i, variant.variantIndex());
            assertEquals(i % 4, variant.yawQuarterTurns(), "variant " + i);
            assertEquals(i >= 4, variant.mirrored(), "variant " + i);
        }
    }

    /**
     * The documented convention IS the transform: re-applying k quarter-turns of
     * (x, y, z) -> (z, y, -x) to the base cells reproduces variant k exactly.
     */
    @Test
    void everyVariantEqualsKManualQuarterTurnsOfTheBase() {
        BlockPattern<String> pattern = asymmetric(true, false);
        for (PatternVariant<String> variant : pattern.variants()) {
            for (int i = 0; i < pattern.cellCount(); i++) {
                PatternCell<String> base = pattern.cells().get(i);
                int x = base.dx();
                int y = base.dy();
                int z = base.dz();
                for (int k = 0; k < variant.yawQuarterTurns(); k++) {
                    int turnedX = z;
                    int turnedZ = -x;
                    x = turnedX;
                    z = turnedZ;
                }
                assertEquals(x, variant.dx(i));
                assertEquals(y, variant.dy(i));
                assertEquals(z, variant.dz(i));
            }
        }
    }

    @Test
    void theAnchorStaysAtTheOriginInEveryVariant() {
        for (PatternVariant<String> variant : asymmetric(true, true).variants()) {
            assertCell(variant, 0, 0, 0, 0);
        }
    }

    @Test
    void anAnchorAuthoredOffOriginIsNormalizedOntoIt() {
        BlockPattern<String> pattern = BlockPattern.compile(List.of(
                new PatternCell<>(5, 1, 5, "alpha"),
                new PatternCell<>(6, 1, 5, "beta"),
                new PatternCell<>(5, 2, 7, "gamma")), 0, false, false);

        assertEquals(0, pattern.anchorIndex());
        assertEquals(0, pattern.cells().get(0).dx());
        assertEquals(0, pattern.cells().get(0).dy());
        assertEquals(0, pattern.cells().get(0).dz());
        assertEquals(1, pattern.cells().get(1).dx());
        assertEquals(2, pattern.cells().get(2).dz());
        assertEquals("gamma", pattern.payload(2), "payloads ride along untouched");
    }

    @Test
    void aNonFirstAnchorNormalizesTheWholeListAroundIt() {
        BlockPattern<String> pattern = BlockPattern.compile(List.of(
                new PatternCell<>(3, 0, 3, "beta"),
                new PatternCell<>(4, 0, 4, "alpha")), 1, false, false);

        assertEquals(1, pattern.anchorIndex());
        assertEquals(-1, pattern.cells().get(0).dx());
        assertEquals(-1, pattern.cells().get(0).dz());
        assertEquals(0, pattern.cells().get(1).dx());
    }

    @Test
    void rotationAndMirrorFlagsDecideTheVariantCount() {
        assertEquals(1, asymmetric(false, false).variants().size());
        assertEquals(2, asymmetric(false, true).variants().size());
        assertEquals(4, asymmetric(true, false).variants().size());
        assertEquals(8, asymmetric(true, true).variants().size());
    }

    @Test
    void boundingRadiusIsTheLargestAbsoluteComponentAfterNormalization() {
        assertEquals(2, asymmetric(true, true).boundingRadius(), "gamma's +2 Z dominates");

        BlockPattern<String> shifted = BlockPattern.compile(List.of(
                new PatternCell<>(10, 10, 10, "alpha"),
                new PatternCell<>(10, 10, 11, "beta")), 0, false, false);
        assertEquals(1, shifted.boundingRadius(), "radius is measured from the anchor, not the authored frame");
    }

    @Test
    void compileRefusesAnEmptyOrBadlyAnchoredPattern() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockPattern.compile(List.of(), 0, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockPattern.compile(List.of(new PatternCell<>(0, 0, 0, "alpha")), 1, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockPattern.compile(List.of(new PatternCell<>(0, 0, 0, "alpha")), -1, false, false));
    }

    @Test
    void aCellRefusesANullPayload() {
        assertThrows(NullPointerException.class, () -> new PatternCell<String>(0, 0, 0, null));
    }

    @Test
    void mirrorFlagsSurviveWithoutRotation() {
        List<PatternVariant<String>> variants = asymmetric(false, true).variants();
        assertFalse(variants.get(0).mirrored());
        assertTrue(variants.get(1).mirrored());
        assertEquals(0, variants.get(0).yawQuarterTurns());
        assertEquals(0, variants.get(1).yawQuarterTurns());
        assertCell(variants.get(1), 1, -1, 0, 0);
    }
}
