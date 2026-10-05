package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.runtime.NpcPlacementService.Upkeep;

/**
 * What the placement service gives a copy it did not just spawn (Update 7, X29 per M123): the Fortify
 * bonus fills its pool only on a fresh spawn or when it was missing, and a copy the sweep keeps or adopts
 * gets the position, pin and bonus a fresh placement gets. Driven through the package-private rules; the
 * engine side is proved by the coordinator's boot.
 */
class NpcPlacementServiceTest {

    @Test
    void fortifyFillsThePoolOnAFreshSpawnOrWhenTheBonusWasMissing() {
        assertTrue(NpcPlacementService.fillsPool(true, false), "a fresh spawn starts at full health");
        assertTrue(NpcPlacementService.fillsPool(false, false),
                "a copy back from a park or an older build gets the bonus and the pool it enlarges");
        assertFalse(NpcPlacementService.fillsPool(false, true),
                "a copy that already had it keeps its health: a sweep or a load never heals it");
        assertTrue(NpcPlacementService.fillsPool(true, true));
    }

    @Test
    void aCopyBackFromAParkGetsItsPositionItsPinAndItsFortify() {
        // X29: an NPC spawned into a sleeping section came back as a load with no ledger row, and
        // adopting it recorded the row and nothing else, so the hub stood without its Fortify bonus,
        // its keep-alive pin and its cached position.
        assertEquals(new Upkeep(true, true, true), NpcPlacementService.upkeepFor(false, false, true, true));
    }

    @Test
    void aKeptCopyKeepsItsOnePinAndItsCachedPosition() {
        assertEquals(new Upkeep(false, false, true), NpcPlacementService.upkeepFor(true, true, true, true),
                "the pin and the cached position were taken together, and the unpin reads that position");
        assertEquals(new Upkeep(false, true, false), NpcPlacementService.upkeepFor(true, false, true, false),
                "a pin missing (refused at placement, or lost to a restart) is taken now, at the cached position");
        assertEquals(new Upkeep(true, false, false), NpcPlacementService.upkeepFor(false, false, false, false),
                "no KeepAlive, no pin; no Fortify authored, no bonus");
    }
}
