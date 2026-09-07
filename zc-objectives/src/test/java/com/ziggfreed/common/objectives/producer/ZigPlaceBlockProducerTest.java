package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.GameMode;
import com.ziggfreed.common.world.BuildPermission;
import com.ziggfreed.common.world.placed.PlacedBlockRecorder;

/**
 * The filters a {@code PLACE_BLOCK} moment passes before it fires: a cancelled placement never
 * happened, an empty or blank item is nothing, a creative-mode placement is exempt, and a placement
 * the world or the environment does not allow never put a block down.
 *
 * <p>{@link ZigPlaceBlockProducer} reads them through the recorder's own
 * {@link PlacedBlockRecorder#placementCounts predicate} - the ONE reading shared with the placement
 * ledger's writer - so pinning the predicate here pins the producer: a placement one of them counts
 * is a placement the other counts, and neither can drift from these rules alone. The ECS half
 * (turning a native event into a store, a ref, a game mode and a position) is what lands behind
 * in-game smoke, as with every other producer.
 */
class ZigPlaceBlockProducerTest {

    @Test
    void anOrdinaryPlacementCounts() {
        assertTrue(PlacedBlockRecorder.placementCounts(false, "Stone", GameMode.Adventure));
        assertTrue(PlacedBlockRecorder.placementCounts(false, "Stone", null),
                "a placer whose game mode could not be read is an ordinary placer");
    }

    @Test
    void aCancelledPlacementNeverHappened() {
        assertFalse(PlacedBlockRecorder.placementCounts(true, "Stone", GameMode.Adventure));
    }

    @Test
    void anEmptyHandPlacedNothing() {
        assertFalse(PlacedBlockRecorder.placementCounts(false, null, GameMode.Adventure));
        assertFalse(PlacedBlockRecorder.placementCounts(false, "", GameMode.Adventure));
        assertFalse(PlacedBlockRecorder.placementCounts(false, "  ", GameMode.Adventure));
        assertFalse(PlacedBlockRecorder.placementCounts(false, "Empty", GameMode.Adventure),
                "the engine's own id for 'nothing here' is not a block anybody placed");
    }

    @Test
    void aCreativeModePlacementIsExempt() {
        assertFalse(PlacedBlockRecorder.placementCounts(false, "Stone", GameMode.Creative),
                "an admin walling in an ore vein is neither building for XP nor placing to break");
    }

    @Test
    void aPlacementTheWorldWillRefuseNeverHappened() {
        assertFalse(BuildPermission.allowsPlacement(false, GameMode.Adventure, true),
                "a world with building turned off hands the item back, so nothing was placed");
        assertFalse(BuildPermission.allowsPlacement(true, GameMode.Adventure, false),
                "a protected environment refuses the block the same way");
    }
}
