package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.NpcPlacementReconciler.PlaceDecision;
import com.ziggfreed.common.npc.placement.NpcPlacementReconciler.PlaceInputs;
import com.ziggfreed.common.npc.placement.NpcPlacementReconciler.ResidentDecision;
import com.ziggfreed.common.npc.placement.NpcPlacementReconciler.ResidentInputs;

/**
 * The reconciler's two pure decision cores.
 *
 * <p>The first test is the regression this entire design exists to prevent, and it is named after
 * it rather than after the API it drives.
 */
class NpcPlacementReconcilerTest {

    private static PlaceInputs place(boolean ledgerHit, boolean chunkLoaded, boolean resident, boolean respawn) {
        return new PlaceInputs(true, true, ledgerHit, chunkLoaded, resident, respawn, false, false);
    }

    // ==================== The double-place regression ====================

    @Test
    void doublePlaceRegression_aLedgerHitWithAnUnloadedChunkNeverPlaces() {
        // A placed NPC whose chunk has gone to sleep is REMOVED from the store, so it looks
        // exactly like an NPC that was never placed. Placing here spawns a second one every time
        // a player walks back into range.
        PlaceDecision decision = NpcPlacementReconciler.decidePlace(
                place(true, false, false, false));

        assertEquals(PlaceDecision.SKIP, decision,
                "a ledger hit plus an unloaded chunk must NEVER place: absence proves nothing while "
                        + "the chunk is asleep, and placing here duplicates every NPC in the world");
        assertNotEquals(PlaceDecision.PLACE, decision);
        assertNotEquals(PlaceDecision.REPLACE, decision);
    }

    @Test
    void doublePlaceRegression_respawnDoesNotDefeatTheUnloadedChunkRule() {
        // Respawn is about an NPC that is genuinely gone. It must not be readable as permission to
        // trust absence while the chunk is asleep.
        assertEquals(PlaceDecision.SKIP, NpcPlacementReconciler.decidePlace(place(true, false, false, true)));
    }

    @Test
    void doublePlaceRegression_noRowAndAnUnloadedChunkAlsoNeverPlaces() {
        // The anchor itself cannot be trusted while the chunk is asleep, so even a genuine ledger
        // miss waits until the chunk is awake.
        assertEquals(PlaceDecision.SKIP, NpcPlacementReconciler.decidePlace(place(false, false, false, false)));
    }

    // ==================== The place rule ====================

    @Test
    void aLedgerMissWithALoadedChunkPlaces() {
        assertEquals(PlaceDecision.PLACE, NpcPlacementReconciler.decidePlace(place(false, true, false, false)));
    }

    @Test
    void aLedgerHitWithTheEntityResidentDoesNothing() {
        assertEquals(PlaceDecision.SKIP, NpcPlacementReconciler.decidePlace(place(true, true, true, true)));
    }

    @Test
    void aGenuinelyMissingNpcIsReplacedOnlyWhenRespawnIsAuthored() {
        assertEquals(PlaceDecision.REPLACE, NpcPlacementReconciler.decidePlace(place(true, true, false, true)));
        assertEquals(PlaceDecision.SKIP, NpcPlacementReconciler.decidePlace(place(true, true, false, false)));
    }

    @Test
    void aDeniedGateOrAMismatchedWorldNeverPlaces() {
        assertEquals(PlaceDecision.SKIP, NpcPlacementReconciler.decidePlace(
                new PlaceInputs(false, true, false, true, false, false, false, false)));
        assertEquals(PlaceDecision.SKIP, NpcPlacementReconciler.decidePlace(
                new PlaceInputs(true, false, false, true, false, false, false, false)));
    }

    @Test
    void capacityBlocksANewInstanceButNotAReplacement() {
        assertEquals(PlaceDecision.SKIP, NpcPlacementReconciler.decidePlace(
                new PlaceInputs(true, true, false, true, false, false, true, false)));
        assertEquals(PlaceDecision.REPLACE, NpcPlacementReconciler.decidePlace(
                new PlaceInputs(true, true, true, true, false, true, true, false)),
                "a replacement occupies a slot the world already counted, so capacity must not block it");
    }

    @Test
    void anInFlightClaimBlocksASecondPass() {
        // Two players entering a fresh instance in the same tick both sweep, and the first add is
        // invisible until the command buffer flushes.
        assertEquals(PlaceDecision.SKIP, NpcPlacementReconciler.decidePlace(
                new PlaceInputs(true, true, false, true, false, false, false, true)));
    }

    // ==================== The resident rule ====================

    @Test
    void aStandingNpcIsKeptWhenEverythingStillAgrees() {
        assertEquals(ResidentDecision.KEEP, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, true, true, true)));
    }

    @Test
    void aDeniedGateDespawnsWhatIsAlreadyStanding() {
        // This is what makes an admin off switch immediate rather than "at the next restart".
        assertEquals(ResidentDecision.DESPAWN, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, false, true, true)));
    }

    @Test
    void aDeletedPlacementOrAWorldThatNoLongerMatchesDespawns() {
        assertEquals(ResidentDecision.DESPAWN, NpcPlacementReconciler.decideResident(
                new ResidentInputs(false, true, true, true)));
        assertEquals(ResidentDecision.DESPAWN, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, true, false, true)));
    }

    @Test
    void aCorrectNpcWithNoLedgerRowIsAdoptedRatherThanReplaced() {
        assertEquals(ResidentDecision.REBIND, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, true, true, false)),
                "removing a correctly-standing NPC just to place an identical one is a visible "
                        + "flicker for no gain");
    }
}
