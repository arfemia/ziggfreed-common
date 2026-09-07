package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.subject.Subject;

/**
 * What a {@code Lootable} reward REPORTS as handed over: the items its roll actually landed, one
 * {@code Item} spec each, plus whatever the table's own {@code Rewards} reported in turn - and
 * nothing at all for a roll that produced nothing. Driven through a real {@link LootEngine} pass
 * over fixture sinks, so the fold is pinned with no server behind it.
 */
class LootableReceiptTest {

    private static Subject subject() {
        return Subject.of(UUID.randomUUID(), "tester");
    }

    private static Roll always(LootGrants grants) {
        return Roll.of(null, null, null, null, grants, null);
    }

    @Test
    void theReceiptListsOneItemPerRolledEntry() {
        LootEngine.Result result = LootEngine.rollAndGrant(
                List.of(always(LootGrants.ofItem("Coin_Gold", 5)), always(LootGrants.ofItem("Gem_Ruby", 1))),
                null, FactorLookup.none(), () -> 0.0,
                LootEngine.Sinks.builder().items((itemId, count) -> count).build());

        List<RewardSpec> handedOver = LootRewardKinds.handedOver(result);

        assertEquals(2, handedOver.size(), "one Item spec per item that landed");
        assertEquals(LootRewardKinds.KIND_ITEM, handedOver.get(0).kind());
        assertEquals("Coin_Gold", handedOver.get(0).param("item"));
        assertEquals(5L, handedOver.get(0).longParam("count", 0L));
        assertEquals("Gem_Ruby", handedOver.get(1).param("item"));
        assertEquals(1L, handedOver.get(1).longParam("count", 0L));
    }

    @Test
    void aNativeDropListsStacksAreListedAsItemsToo() {
        LootEngine.Result result = LootEngine.rollAndGrant(
                List.of(always(LootGrants.ofDropList("Demo_List"))),
                null, FactorLookup.none(), () -> 0.0,
                LootEngine.Sinks.builder().dropLists(id -> Map.of("Bone", 2)).build());

        List<RewardSpec> handedOver = LootRewardKinds.handedOver(result);

        assertEquals(1, handedOver.size());
        assertEquals("Bone", handedOver.get(0).param("item"));
        assertEquals(2L, handedOver.get(0).longParam("count", 0L));
    }

    @Test
    void anEmptyRollReportsNothing() {
        // Every item goes to an absent sink, so nothing lands and the receipt says so.
        LootEngine.Result result = LootEngine.rollAndGrant(
                List.of(always(LootGrants.ofItem("Coin_Gold", 5))),
                null, FactorLookup.none(), () -> 0.0, LootEngine.Sinks.NONE);

        assertTrue(LootRewardKinds.handedOver(result).isEmpty(),
                "a roll that landed nothing adds no row, not a generic one");
    }

    @Test
    void aTablesOwnRewardsFoldInAsWhatTheyReported() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        kinds.register("Test_Currency", "test", (spec, s) -> { });
        LootGrants grants = LootGrants.of(new LootGrants.Item[] {LootGrants.Item.of("Coin_Gold", 1)}, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("Test_Currency", Map.of("Amount", "25"))});

        LootEngine.Result result = LootEngine.rollAndGrant(List.of(always(grants)), null,
                FactorLookup.none(), () -> 0.0,
                LootEngine.Sinks.builder().items((itemId, count) -> count).rewards(kinds, subject()).build());

        List<RewardSpec> handedOver = LootRewardKinds.handedOver(result);

        assertEquals(2, handedOver.size(), "the rolled item, then the currency the table granted");
        assertEquals("Coin_Gold", handedOver.get(0).param("item"));
        assertEquals("Test_Currency", handedOver.get(1).kind());
        assertEquals("25", handedOver.get(1).param("amount"),
                "a table granting a currency names the currency rather than vanishing");
    }
}
