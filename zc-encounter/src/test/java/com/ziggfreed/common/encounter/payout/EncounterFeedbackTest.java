package com.ziggfreed.common.encounter.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The per-participant split of a settlement moment: each player's map carries their own share,
 * their own rank and, when their roll put anything in their hands, their OWN receipt - never
 * another player's, and never a generic row for an empty roll.
 */
class EncounterFeedbackTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);

    private static Map<String, Object> shared() {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put(EncounterFeedback.ENCOUNTER_ARG, "Demo_Boss");
        shared.put(EncounterFeedback.MEMBERS_ARG, 2);
        return shared;
    }

    @Test
    void aParticipantSeesTheirOwnRollAndNobodyElses() {
        List<RewardSpec> alices = List.of(RewardSpec.of("Item", Map.of("Item", "Coin_Gold", "Count", "3")));
        Map<UUID, List<RewardSpec>> receipts = Map.of(ALICE, alices);

        Map<String, Object> own = EncounterFeedback.ownArgs(shared(), 1.0, 1, receipts.get(ALICE));
        Map<String, Object> bobs = EncounterFeedback.ownArgs(shared(), 0.4, 2, receipts.get(BOB));

        assertEquals(alices, own.get(FeedbackEngine.REWARDS_ARG), "the top contributor lists what they rolled");
        assertEquals(100L, own.get(EncounterFeedback.SHARE_ARG));
        assertEquals(1, own.get(EncounterFeedback.RANK_ARG));
        assertFalse(bobs.containsKey(FeedbackEngine.REWARDS_ARG),
                "a player whose roll landed nothing carries no rewards at all, and never Alice's");
        assertEquals(40L, bobs.get(EncounterFeedback.SHARE_ARG));
        assertEquals(2, bobs.get(EncounterFeedback.RANK_ARG));
    }

    @Test
    void anEmptyReceiptAddsNoRewardsArgument() {
        Map<String, Object> own = EncounterFeedback.ownArgs(shared(), 0.7, 1, List.of());
        assertFalse(own.containsKey(FeedbackEngine.REWARDS_ARG),
                "an empty roll shows the headline alone rather than a generic row");
    }

    @Test
    void everyBeatCarriesTheRunsWaveCount() {
        Map<String, Object> beat = EncounterFeedback.beatArgs("Demo_Boss", Message.raw("Demo Boss"), 2, 30L, null, 3);
        assertEquals(3, beat.get(EncounterFeedback.WAVE_ARG),
                "a moment line written Args [\"wave\"] reads the run's wave count; a missing argument drops the line");
        assertEquals("Demo_Boss", beat.get(EncounterFeedback.ENCOUNTER_ARG));
        assertFalse(beat.containsKey(EncounterFeedback.PHASE_ARG), "no phase yet carries no phase argument");
        assertEquals(0, EncounterFeedback.beatArgs("Demo_Boss", Message.raw("Demo Boss"), 0, 0L, "Phase_1", 0)
                .get(EncounterFeedback.WAVE_ARG), "a run with no wave yet still carries the argument, as 0");
    }

    @Test
    void theSharedBeatIsNeverWrittenTo() {
        Map<String, Object> shared = shared();
        EncounterFeedback.ownArgs(shared, 1.0, 1,
                List.of(RewardSpec.of("Item", Map.of("Item", "Coin_Gold", "Count", "1"))));
        assertFalse(shared.containsKey(FeedbackEngine.REWARDS_ARG));
        assertFalse(shared.containsKey(EncounterFeedback.SHARE_ARG));
        assertTrue(shared.containsKey(EncounterFeedback.ENCOUNTER_ARG),
                "the shared beat stays what the caller composed");
    }
}
