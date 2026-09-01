package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.anchor.AnchorPosition.AnchorKind;
import com.ziggfreed.common.npc.placement.anchor.AnchorPosition;

/**
 * The multi-anchor rules: the union, the {@code OncePerWorld} collapse order, the
 * {@code MaxPerWorld} count across the whole union, and the determinism of {@code SpawnChance}.
 */
class PlacementAnchorsTest {

    private static AnchorPosition at(AnchorKind kind, String instanceId) {
        return new AnchorPosition(kind, instanceId, 0, 0, 0, 0f);
    }

    /** The union a placement authoring every group would produce, in declaration order. */
    private static List<AnchorPosition> fullUnion() {
        return List.of(
                at(AnchorKind.WORLD_SPAWN, "0"),
                at(AnchorKind.COORDS, "0"),
                at(AnchorKind.STRUCTURE, "1001"),
                at(AnchorKind.STRUCTURE, "1002"),
                at(AnchorKind.ZONE, "frostvale"),
                at(AnchorKind.CUSTOM, "yourmod:station#a"));
    }

    // ==================== instance identity ====================

    @Test
    void everyPositionGetsItsOwnInstanceKey() {
        assertEquals("structure:1001", at(AnchorKind.STRUCTURE, "1001").anchorKey());
        assertEquals("structure:1002", at(AnchorKind.STRUCTURE, "1002").anchorKey());
        assertEquals("worldspawn:0", at(AnchorKind.WORLD_SPAWN, "0").anchorKey());
    }

    @Test
    void twoGroupsNeverCollideOnAnAnchorKey() {
        assertTrue(fullUnion().stream().map(AnchorPosition::anchorKey).distinct().count()
                == fullUnion().size(),
                "each union member must be an independently addressable instance");
    }

    // ==================== OncePerWorld ====================

    @Test
    void oncePerWorldCollapsesToTheFirstInDeclarationOrder() {
        List<AnchorPosition> collapsed = PlacementAnchors.collapse(fullUnion(), true);

        assertEquals(1, collapsed.size());
        assertEquals(AnchorKind.WORLD_SPAWN, collapsed.get(0).kind(),
                "the order is WorldSpawn, Coords, Structure, Zone, Custom, so which one survives is "
                        + "readable off the file rather than dependent on chunk-load timing");
    }

    @Test
    void oncePerWorldFallsThroughToTheFirstGroupThatActuallyResolved() {
        List<AnchorPosition> union = List.of(
                at(AnchorKind.STRUCTURE, "1001"),
                at(AnchorKind.ZONE, "frostvale"));

        assertEquals(AnchorKind.STRUCTURE, PlacementAnchors.collapse(union, true).get(0).kind());
    }

    @Test
    void withoutOncePerWorldTheWholeUnionSurvives() {
        assertEquals(6, PlacementAnchors.collapse(fullUnion(), false).size());
    }

    @Test
    void collapsingAnEmptyOrSingleUnionIsSafe() {
        assertTrue(PlacementAnchors.collapse(List.of(), true).isEmpty());
        assertEquals(1, PlacementAnchors.collapse(List.of(at(AnchorKind.COORDS, "0")), true).size());
    }

    // ==================== MaxPerWorld ====================

    @Test
    void maxPerWorldCountsAcrossTheWholeUnionNotPerGroup() {
        List<AnchorPosition> capped = PlacementAnchors.applyMaxPerWorld(fullUnion(), 2, 0);

        assertEquals(2, capped.size(),
                "two structures plus a world spawn is three of this NPC in one world, whichever "
                        + "groups they came from");
    }

    @Test
    void maxPerWorldSubtractsWhatIsAlreadyPlaced() {
        assertEquals(1, PlacementAnchors.applyMaxPerWorld(fullUnion(), 3, 2).size());
        assertTrue(PlacementAnchors.applyMaxPerWorld(fullUnion(), 3, 3).isEmpty());
        assertTrue(PlacementAnchors.applyMaxPerWorld(fullUnion(), 3, 9).isEmpty());
    }

    @Test
    void aNonPositiveMaxMeansUnlimited() {
        assertEquals(6, PlacementAnchors.applyMaxPerWorld(fullUnion(), 0, 100).size());
        assertEquals(6, PlacementAnchors.applyMaxPerWorld(fullUnion(), -1, 100).size());
    }

    // ==================== SpawnChance ====================

    @Test
    void aFullChanceKeepsEverythingAndAZeroChanceKeepsNothing() {
        assertEquals(6, PlacementAnchors.applyChance(fullUnion(), 42L, "hub", 1.0).size());
        assertTrue(PlacementAnchors.applyChance(fullUnion(), 42L, "hub", 0.0).isEmpty());
    }

    @Test
    void theRollIsDeterministicPerInstance() {
        double first = PlacementAnchors.roll(42L, "hub", "structure:1001");
        double second = PlacementAnchors.roll(42L, "hub", "structure:1001");

        assertEquals(first, second,
                "an identical input must give an identical decision forever, or a chunk reload "
                        + "re-rolls the world's population every time a player walks past");
        assertTrue(first >= 0.0 && first < 1.0);
    }

    @Test
    void theRollDiffersPerInstanceAndPerPlacementAndPerWorld() {
        double a = PlacementAnchors.roll(42L, "hub", "structure:1001");
        assertTrue(a != PlacementAnchors.roll(42L, "hub", "structure:1002"));
        assertTrue(a != PlacementAnchors.roll(42L, "shop", "structure:1001"));
        assertTrue(a != PlacementAnchors.roll(43L, "hub", "structure:1001"));
    }

    @Test
    void aPartialChanceFiltersReproducibly() {
        List<AnchorPosition> once = PlacementAnchors.applyChance(fullUnion(), 42L, "hub", 0.5);
        List<AnchorPosition> twice = PlacementAnchors.applyChance(fullUnion(), 42L, "hub", 0.5);

        assertEquals(once.stream().map(AnchorPosition::anchorKey).toList(),
                twice.stream().map(AnchorPosition::anchorKey).toList());
    }
}
