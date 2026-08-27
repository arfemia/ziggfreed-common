package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootCues;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.subject.Subject;

/**
 * The {@code Lootable} reward kind's sink wiring, driven without a live server: a rolled table's
 * OWN {@code Rewards} entries must genuinely pay through the registry the kind was registered
 * into, and the pass's earned cues must reach whatever presenter the server registered. Both are
 * the promises the kind's own javadoc makes - a reward that reports PAID while paying nothing is
 * the exact failure the fail-loud rule exists to prevent.
 */
class LootableRewardSinksTest {

    private Subject subject;
    private RewardKindRegistry kinds;
    private List<String> paid;

    @BeforeEach
    void setUp() {
        subject = Subject.of(UUID.randomUUID(), "tester");
        kinds = new RewardKindRegistry();
        paid = new ArrayList<>();
        kinds.register("Test_Point", (spec, s) -> paid.add(spec.paramOr("amount", "?")));
    }

    @AfterEach
    void tearDown() {
        LootCues.clear();
    }

    @Test
    void aRewardsOnlyRollPaysThroughTheLootableSinks() {
        LootGrants grants = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("Test_Point", Map.of("Amount", "1"))});
        RewardSpec spec = RewardSpec.of(LootRewardKinds.KIND_LOOTABLE, Map.of("Lootable", "demo"));

        LootEngine.Result result = LootEngine.rollAndGrant(
                List.of(Roll.of(null, null, null, null, grants, "rare_find")),
                null, FactorLookup.none(), () -> 0.0,
                LootRewardKinds.lootableSinks(spec, subject, kinds, "reward:demo"));

        assertEquals(List.of("1"), paid,
                "a table's own Rewards entry must actually pay, not report PAID over nothing");
        assertEquals(1, result.getRewardsPaid());
        assertTrue(result.anyGranted());
        assertEquals(List.of("rare_find"), result.getCues(),
                "a cue beside grants is earned once those grants genuinely paid");
    }

    @Test
    void earnedCuesReachTheRegisteredPresenterInOrder() {
        List<String> presented = new ArrayList<>();
        LootCues.register((cueId, s, sourceId) -> presented.add(cueId + "@" + sourceId));

        LootCues.presentAll(List.of("first", " ", "second"), subject, "reward:demo");

        assertEquals(List.of("first@reward:demo", "second@reward:demo"), presented,
                "every earned cue is presented in order and a blank one is skipped");
    }

    @Test
    void withNoPresenterForwardingIsQuiet() {
        assertDoesNotThrow(() -> LootCues.presentAll(List.of("rare_find"), subject, "reward:demo"),
                "a server with no presenter registered behaves exactly as one did before the seam");
    }

    @Test
    void aThrowingPresenterCostsOnlyItsOwnCue() {
        List<String> presented = new ArrayList<>();
        LootCues.register((cueId, s, sourceId) -> {
            if ("broken".equals(cueId)) {
                throw new IllegalStateException("no");
            }
            presented.add(cueId);
        });

        assertDoesNotThrow(() -> LootCues.presentAll(List.of("broken", "fine"), subject, "reward:demo"));
        assertEquals(List.of("fine"), presented,
                "a presenter that throws must never cost the cues after it, or the grant");
    }
}
