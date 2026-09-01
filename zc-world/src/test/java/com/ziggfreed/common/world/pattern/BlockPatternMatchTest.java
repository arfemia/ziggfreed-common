package com.ziggfreed.common.world.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

/**
 * The matcher over a stubbed reader: a tiny in-memory world where every unmapped position reads as
 * the engine's empty key. Payloads ARE the expected block ids (the simplest predicate a caller
 * could write), which keeps every assertion about the walk itself rather than about any matching
 * vocabulary; the library has none.
 */
class BlockPatternMatchTest {

    private static final String EMPTY = "Empty";

    /** A stub world: unmapped positions answer the empty key; a null default simulates unloaded. */
    private static final class StubWorld {
        final Map<String, String> blocks = new HashMap<>();
        String defaultAnswer = EMPTY;

        void put(int x, int y, int z, String blockItemId) {
            blocks.put(x + "," + y + "," + z, blockItemId);
        }

        BlockReader reader() {
            return (x, y, z) -> blocks.getOrDefault(x + "," + y + "," + z, defaultAnswer);
        }
    }

    private static final CellPredicate<String> EXACT = String::equals;

    private static BlockPattern<String> asymmetric() {
        return BlockPattern.compile(List.of(
                new PatternCell<>(0, 0, 0, "Block_Alpha"),
                new PatternCell<>(1, 0, 0, "Block_Beta"),
                new PatternCell<>(0, 1, 2, "Block_Gamma")), 0, true, true);
    }

    /** Stamp one variant of the pattern into the stub world at this anchor. */
    private static void place(StubWorld world, PatternVariant<String> variant,
            int ax, int ay, int az) {
        for (int i = 0; i < variant.cellCount(); i++) {
            world.put(ax + variant.dx(i), ay + variant.dy(i), az + variant.dz(i),
                    variant.pattern().payload(i));
        }
    }

    @Test
    void everyVariantMatchesAtTheAnchorItWasPlacedAt() {
        BlockPattern<String> pattern = asymmetric();
        for (PatternVariant<String> variant : pattern.variants()) {
            StubWorld world = new StubWorld();
            place(world, variant, 10, 20, 30);
            assertTrue(variant.matchAt(10, 20, 30, world.reader(), EXACT),
                    "variant " + variant.variantIndex() + " must match its own placement");
        }
    }

    /** The fixture is asymmetric, so a world holding one orientation matches ONLY that variant. */
    @Test
    void onlyThePlacedOrientationMatches() {
        BlockPattern<String> pattern = asymmetric();
        StubWorld world = new StubWorld();
        place(world, pattern.variants().get(1), 10, 20, 30);

        for (PatternVariant<String> variant : pattern.variants()) {
            boolean matched = variant.matchAt(10, 20, 30, world.reader(), EXACT);
            assertEquals(variant.variantIndex() == 1, matched,
                    "variant " + variant.variantIndex());
        }
    }

    @Test
    void aWrongBlockAnywhereFailsTheMatch() {
        BlockPattern<String> pattern = asymmetric();
        PatternVariant<String> identity = pattern.variants().get(0);
        StubWorld world = new StubWorld();
        place(world, identity, 0, 0, 0);
        world.put(1, 0, 0, "Block_Delta");
        assertFalse(identity.matchAt(0, 0, 0, world.reader(), EXACT));
    }

    @Test
    void theWalkStopsAtTheFirstFailingCell() {
        BlockPattern<String> pattern = asymmetric();
        PatternVariant<String> identity = pattern.variants().get(0);
        StubWorld world = new StubWorld();
        place(world, identity, 0, 0, 0);
        world.put(0, 0, 0, "Block_Delta");

        AtomicInteger reads = new AtomicInteger();
        AtomicInteger tests = new AtomicInteger();
        BlockReader counting = (x, y, z) -> {
            reads.incrementAndGet();
            return world.reader().blockItemIdAt(x, y, z);
        };
        CellPredicate<String> countingPredicate = (payload, id) -> {
            tests.incrementAndGet();
            return payload.equals(id);
        };

        assertFalse(identity.matchAt(0, 0, 0, counting, countingPredicate));
        assertEquals(1, reads.get(), "the anchor cell fails first, nothing after it is read");
        assertEquals(1, tests.get());
    }

    @Test
    void anUnreadablePositionFailsBeforeThePredicateIsConsulted() {
        BlockPattern<String> pattern = asymmetric();
        PatternVariant<String> identity = pattern.variants().get(0);
        StubWorld world = new StubWorld();
        place(world, identity, 0, 0, 0);
        world.defaultAnswer = null;
        world.blocks.remove("1,0,0");

        AtomicInteger tests = new AtomicInteger();
        CellPredicate<String> countingPredicate = (payload, id) -> {
            tests.incrementAndGet();
            return true;
        };

        assertFalse(identity.matchAt(0, 0, 0, world.reader(), countingPredicate));
        assertEquals(1, tests.get(), "only the anchor cell was readable, so only it was tested");
    }

    @Test
    void thePredicateReceivesThePayloadAndTheBlockReadAtItsCell() {
        BlockPattern<String> pattern = BlockPattern.compile(List.of(
                new PatternCell<>(0, 0, 0, "alpha"),
                new PatternCell<>(0, 3, 0, "beta")), 0, false, false);
        StubWorld world = new StubWorld();
        world.put(7, 8, 9, "Block_Alpha");
        world.put(7, 11, 9, "Block_Beta");

        Map<String, String> seen = new HashMap<>();
        assertTrue(pattern.variants().get(0).matchAt(7, 8, 9, world.reader(), (payload, id) -> {
            seen.put(payload, id);
            return true;
        }));
        assertEquals("Block_Alpha", seen.get("alpha"));
        assertEquals("Block_Beta", seen.get("beta"));
    }

    @Test
    void deriveFromCellRecoversTheAnchorForEveryVariantAndCell() {
        BlockPattern<String> pattern = asymmetric();
        for (PatternVariant<String> variant : pattern.variants()) {
            for (int i = 0; i < variant.cellCount(); i++) {
                int placedX = 100 + variant.dx(i);
                int placedY = 50 + variant.dy(i);
                int placedZ = -20 + variant.dz(i);
                Vector3i anchor = variant.anchorFromCell(i, placedX, placedY, placedZ);
                assertEquals(100, anchor.x, "variant " + variant.variantIndex() + " cell " + i);
                assertEquals(50, anchor.y);
                assertEquals(-20, anchor.z);
            }
        }
    }

    @Test
    void matchFromCellAnswersTheCompletedMatchForEveryVariantAndCell() {
        BlockPattern<String> pattern = asymmetric();
        for (PatternVariant<String> variant : pattern.variants()) {
            StubWorld world = new StubWorld();
            place(world, variant, 100, 50, -20);
            for (int i = 0; i < variant.cellCount(); i++) {
                PatternMatch<String> match = variant.matchFromCell(i,
                        100 + variant.dx(i), 50 + variant.dy(i), -20 + variant.dz(i),
                        world.reader(), EXACT);
                assertNotNull(match, "variant " + variant.variantIndex() + " cell " + i);
                assertSame(pattern, match.pattern());
                assertEquals(variant.variantIndex(), match.variantIndex());
                assertEquals(100, match.anchorX());
                assertEquals(50, match.anchorY());
                assertEquals(-20, match.anchorZ());
                assertEquals(variant.yawQuarterTurns(), match.yawQuarterTurns());
                assertEquals(variant.mirrored(), match.mirrored());
                assertEquals(new Vector3i(100, 50, -20), match.anchor());
            }
        }
    }

    @Test
    void matchFromCellAnswersNullWhenTheWorldDoesNotHoldThePattern() {
        BlockPattern<String> pattern = asymmetric();
        PatternVariant<String> identity = pattern.variants().get(0);
        StubWorld world = new StubWorld();
        assertNull(identity.matchFromCell(0, 0, 0, 0, world.reader(), EXACT));
    }

    /** Probing from the anchor cell is derive-from-cell with the anchor's own index. */
    @Test
    void probingFromTheAnchorCellIsTheSameEntryPoint() {
        BlockPattern<String> pattern = asymmetric();
        PatternVariant<String> identity = pattern.variants().get(0);
        StubWorld world = new StubWorld();
        place(world, identity, 4, 5, 6);

        PatternMatch<String> match = identity.matchFromCell(pattern.anchorIndex(), 4, 5, 6,
                world.reader(), EXACT);
        assertNotNull(match);
        assertEquals(4, match.anchorX());
        assertEquals(5, match.anchorY());
        assertEquals(6, match.anchorZ());
    }
}
