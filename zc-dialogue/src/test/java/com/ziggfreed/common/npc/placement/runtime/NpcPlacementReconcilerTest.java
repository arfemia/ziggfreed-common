package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.AdoptedRow;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.AdoptionPlan;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.PlaceDecision;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.PlaceInputs;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.ResidentDecision;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.ResidentInputs;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.Round;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.SweepSummary;

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
                new ResidentInputs(true, true, true, true, true)));
    }

    @Test
    void aDeniedGateDespawnsWhatIsAlreadyStanding() {
        // This is what makes an admin off switch immediate rather than "at the next restart".
        assertEquals(ResidentDecision.DESPAWN, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, false, true, true, true)));
    }

    @Test
    void aDeletedPlacementOrAWorldThatNoLongerMatchesDespawns() {
        assertEquals(ResidentDecision.DESPAWN, NpcPlacementReconciler.decideResident(
                new ResidentInputs(false, true, true, true, true)));
        assertEquals(ResidentDecision.DESPAWN, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, true, false, true, true)));
    }

    @Test
    void aCorrectNpcWithNoLedgerRowIsAdoptedRatherThanReplaced() {
        assertEquals(ResidentDecision.REBIND, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, true, true, false, false)),
                "removing a correctly-standing NPC just to place an identical one is a visible "
                        + "flicker for no gain");
    }

    // ==================== The stacking regression ====================

    @Test
    void aSurplusDuplicateWhoseRowNamesSomeoneElseIsDespawnedNotAdopted() {
        // A row EXISTING is not the same as a row naming THIS entity. Two entities can share one
        // (placement, anchor) after an earlier REPLACE (the ledger-recorded one was briefly absent
        // when a sweep ran, so a new one was placed and the row re-pointed to it); treating
        // "a row exists" as "this entity matches" is exactly what let the extra one stand forever
        // and stack one higher every time it happened again.
        assertEquals(ResidentDecision.DESPAWN, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, true, true, true, false)),
                "a row that exists but names a different entity is a surplus duplicate, not an "
                        + "adoption candidate");
        assertNotEquals(ResidentDecision.REBIND, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, true, true, true, false)));
    }

    // ==================== Update 7: the anchor's chunk section (X29) ====================

    private static Round round(int placed, int unresolved, boolean wokeASection) {
        return new Round(new SweepSummary(3, 1, 0, placed, unresolved), wokeASection);
    }

    @Test
    void aRoundThatWokeNoSectionIsTheWholeSweep() {
        int[] rounds = {0};
        SweepSummary summary = NpcPlacementReconciler.settleRounds(() -> {
            rounds[0]++;
            return round(1, 0, false);
        });

        assertEquals(1, rounds[0]);
        assertEquals(1, summary.placed());
    }

    @Test
    void aRoundThatWokeASectionRunsOnceMoreAndItsCountsAddUp() {
        // The first round woke the hub's sleeping section and placed nothing into it; the second round's
        // despawn pass adopts anything the wake brought back, then its place pass places.
        Deque<Round> script = new ArrayDeque<>(List.of(round(0, 1, true), round(1, 0, false)));

        SweepSummary summary = NpcPlacementReconciler.settleRounds(script::poll);

        assertTrue(script.isEmpty(), "the round after the wake ran");
        assertEquals(1, summary.placed(), "placed on the second round");
        assertEquals(2, summary.despawned(), "both rounds' removals count");
        assertEquals(3, summary.scanned(), "the last round's scan");
        assertEquals(0, summary.unresolvedAnchors(), "the retry signal is the last round's");
    }

    @Test
    void aSecondRoundThatWakesAgainNeverStartsAThird() {
        int[] rounds = {0};
        SweepSummary summary = NpcPlacementReconciler.settleRounds(() -> {
            rounds[0]++;
            return round(0, 1, true);
        });

        assertEquals(2, rounds[0]);
        assertEquals(1, summary.unresolvedAnchors(), "still open, so the retry chain carries on");
    }

    @Test
    void aParkedPileThatComesBackIsAdoptedOnceAndTheRestRemoved() {
        // A build that spawned into sleeping sections parked a copy on every pass, and the section saved
        // them all; one wake brings them all back at once, and each reads "no row" in the same walk.
        List<AdoptedRow> walked = List.of(
                new AdoptedRow("hub", "worldspawn:0", UUID.fromString("00000000-0000-0000-0000-000000000001")),
                new AdoptedRow("hub", "worldspawn:0", UUID.fromString("00000000-0000-0000-0000-000000000002")),
                new AdoptedRow("hub", "worldspawn:0", UUID.fromString("00000000-0000-0000-0000-000000000003")));

        AdoptionPlan plan = NpcPlacementReconciler.planAdoptions(walked);

        assertEquals(List.of(walked.get(0)), plan.keep(), "one row for the instance");
        assertEquals(List.of(walked.get(1), walked.get(2)), plan.surplus(), "the other copies go now");
    }

    @Test
    void distinctInstancesAreEachAdopted() {
        List<AdoptedRow> walked = List.of(
                new AdoptedRow("hub", "worldspawn:0", UUID.fromString("00000000-0000-0000-0000-000000000001")),
                new AdoptedRow("hub", "coords:1", UUID.fromString("00000000-0000-0000-0000-000000000002")),
                new AdoptedRow("guide", "worldspawn:0", UUID.fromString("00000000-0000-0000-0000-000000000003")));

        AdoptionPlan plan = NpcPlacementReconciler.planAdoptions(walked);

        assertEquals(walked, plan.keep());
        assertTrue(plan.surplus().isEmpty());
    }

    @Test
    void onlyASectionThatLandedSweepsTheWorldAgain() {
        // A failed or empty load must not sweep again by itself: the next sweep would ask for the same
        // section, a chunk store on its failure backoff answers at once, and the world thread would spin.
        Ref<ChunkStore> landed = new Ref<>((Store<ChunkStore>) null, 7);
        assertTrue(NpcPlacementReconciler.sweepAfterLanding(landed, null));
        assertFalse(NpcPlacementReconciler.sweepAfterLanding(null, null));
        assertFalse(NpcPlacementReconciler.sweepAfterLanding(null, new IllegalStateException("generation failed")));
        assertFalse(NpcPlacementReconciler.sweepAfterLanding(landed, new IllegalStateException("late failure")));
    }

    @Test
    void aSectionRefThatIsNoLongerValidIsNoLanding() {
        // The section went out of memory again before the completion ran, so its ref is invalid: a sweep
        // now would read the section absent and ask for it again at once.
        assertFalse(NpcPlacementReconciler.sweepAfterLanding(new Ref<>((Store<ChunkStore>) null), null));
    }
}
