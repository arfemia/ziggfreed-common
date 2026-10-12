package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.ziggfreed.common.loot.LootPool;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
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
        LootableConfig.getInstance().mergePackLayer(Map.of());
    }

    /**
     * A table authored as a pool alone, with no rolls, pays from its pool when a {@code Lootable} reward
     * rolls it: the reward resolves the whole table, never its rolls alone, and it fails only on a table
     * with neither.
     */
    @Test
    void aPoolOnlyTablePaidThroughALootableRewardGrantsFromItsPool() {
        LootGrants grants = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("Test_Point", Map.of("Amount", "7"))});
        LootPool pool = LootPool.of(null, new LootPool.Entry[] {LootPool.Entry.of(1.0, null, grants)});
        LootableConfig.getInstance().mergePackLayer(
                Map.of("fixture_pool_only", LootableAsset.of("fixture_pool_only", null, pool, null)));
        RewardSpec spec = RewardSpec.of(LootRewardKinds.KIND_LOOTABLE, Map.of("Lootable", "fixture_pool_only"));

        LootEngine.Result result = LootRewardKinds.rollTable(LootRewardKinds.resolveTable("fixture_pool_only"),
                null, FactorLookup.none(), () -> 0.0,
                LootRewardKinds.lootableSinks(spec, subject, kinds, "reward:fixture_pool_only"));

        assertEquals(List.of("7"), paid, "the pool's pick pays through the Lootable reward");
        assertEquals(1, result.getRewardsPaid());
        assertThrows(IllegalStateException.class, () -> LootRewardKinds.resolveTable("fixture_no_such_table"),
                "a table with neither rolls nor a pool still fails loud");
    }

    /** A table with both rolls and a pool pays both through a {@code Lootable} reward: its rolls, then its pick. */
    @Test
    void aTableWithRollsAndAPoolPaysBothThroughALootableReward() {
        LootGrants rolled = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("Test_Point", Map.of("Amount", "1"))});
        LootGrants picked = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("Test_Point", Map.of("Amount", "2"))});
        LootPool pool = LootPool.of(null, new LootPool.Entry[] {LootPool.Entry.of(1.0, null, picked)});
        LootableConfig.getInstance().mergePackLayer(Map.of("fixture_both", LootableAsset.of("fixture_both",
                new Roll[] {Roll.of(null, null, null, null, rolled, null)}, pool, null)));
        RewardSpec spec = RewardSpec.of(LootRewardKinds.KIND_LOOTABLE, Map.of("Lootable", "fixture_both"));

        LootEngine.Result result = LootRewardKinds.rollTable(LootRewardKinds.resolveTable("fixture_both"),
                null, FactorLookup.none(), () -> 0.0,
                LootRewardKinds.lootableSinks(spec, subject, kinds, "reward:fixture_both"));

        assertEquals(List.of("1", "2"), paid, "the roll pays, then the pool's pick, never the roll alone");
        assertEquals(2, result.getRewardsPaid());
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
