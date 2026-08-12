package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * Applying what a roll decided, with every effect replaced by a fixture sink - which is exactly what
 * the seams are for. The load-bearing cases are the ones where "produced something" and "tried
 * something" differ, because that difference is what keeps a fanfare from playing over an empty hand.
 */
class LootEngineTest {

    /** An item sink that records what it was asked for and hands over up to a fixed capacity. */
    static final class RecordingItems implements LootEngine.ItemSink {
        final List<String> asked = new ArrayList<>();
        int capacity = Integer.MAX_VALUE;

        @Override
        public int deliver(String itemId, int count) {
            asked.add(itemId + " x" + count);
            int delivered = Math.min(count, capacity);
            capacity -= delivered;
            return delivered;
        }
    }

    static LootEngine.Sinks itemsOnly(LootEngine.ItemSink items) {
        return LootEngine.Sinks.builder().items(items).build();
    }

    static Roll alwaysGranting(LootGrants grants, String cue) {
        return Roll.of(null, null, null, null, grants, cue);
    }

    // ==================== items ====================

    @Nested
    class Items {

        @Test
        void anItemEntryIsHandedOverWithItsCount() {
            RecordingItems items = new RecordingItems();
            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(LootGrants.ofItem("Coin_Gold", 5), null)),
                    null, FactorLookup.none(), () -> 0.0, itemsOnly(items));

            assertEquals(List.of("Coin_Gold x5"), items.asked);
            assertEquals(Map.of("Coin_Gold", 5), result.getItems());
            assertTrue(result.anyGranted());
        }

        @Test
        void anOmittedCountHandsOverOne() {
            RecordingItems items = new RecordingItems();
            LootGrants grants = LootGrants.of(
                    new LootGrants.Item[] {LootGrants.Item.of("Gem_Ruby", null)}, null, null, null);
            LootEngine.rollAndGrant(List.of(alwaysGranting(grants, null)), null, FactorLookup.none(),
                    () -> 0.0, itemsOnly(items));
            assertEquals(List.of("Gem_Ruby x1"), items.asked);
        }

        @Test
        void aPartialDeliveryReportsOnlyWhatLanded() {
            RecordingItems items = new RecordingItems();
            items.capacity = 2;
            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(LootGrants.ofItem("Coin_Gold", 5), null)),
                    null, FactorLookup.none(), () -> 0.0, itemsOnly(items));
            assertEquals(Map.of("Coin_Gold", 2), result.getItems(),
                    "a full inventory means fewer items landed, not five that vanished");
        }

        @Test
        void withNoItemSinkNothingIsHandedOverAndNothingIsClaimed() {
            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(LootGrants.ofItem("Coin_Gold", 5), null)),
                    null, FactorLookup.none(), () -> 0.0, LootEngine.Sinks.NONE);
            assertTrue(result.getItems().isEmpty());
            assertFalse(result.anyGranted());
        }
    }

    // ==================== drop lists ====================

    @Nested
    class DropLists {

        @Test
        void eachTableRollsIndependentlyInAuthoredOrder() {
            List<String> rolled = new ArrayList<>();
            LootEngine.Sinks sinks = LootEngine.Sinks.builder()
                    .dropLists(id -> {
                        rolled.add(id);
                        return Map.of(id + "_Item", 1);
                    })
                    .build();

            LootGrants grants = LootGrants.of(null, new String[] {"Common", "Rare"}, null, null);
            LootEngine.Result result = LootEngine.rollAndGrant(List.of(alwaysGranting(grants, null)),
                    null, FactorLookup.none(), () -> 0.0, sinks);

            assertEquals(List.of("Common", "Rare"), rolled);
            assertEquals(2, result.getItems().size());
        }

        @Test
        void aTableThatRolledNothingProducesNothing() {
            LootEngine.Sinks sinks = LootEngine.Sinks.builder().dropLists(id -> Map.of()).build();
            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(LootGrants.ofDropList("Empty"), null)),
                    null, FactorLookup.none(), () -> 0.0, sinks);
            assertFalse(result.anyGranted());
        }

        @Test
        void aThrowingTableCostsOnlyItsOwnLine() {
            List<String> warnings = new ArrayList<>();
            LootEngine.Sinks sinks = LootEngine.Sinks.builder()
                    .dropLists(id -> {
                        throw new IllegalStateException("engine said no");
                    })
                    .warn(warnings::add)
                    .build();
            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(LootGrants.ofDropList("Broken"), null)),
                    null, FactorLookup.none(), () -> 0.0, sinks);
            assertFalse(result.anyGranted());
            assertEquals(1, warnings.size());
        }
    }

    // ==================== the smart-cue rule ====================

    @Nested
    class Cues {

        @Test
        void aCueWithNoGrantsBesideItAlwaysPlaysOnTheHit() {
            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(null, "pure_cue")),
                    null, FactorLookup.none(), () -> 0.0, LootEngine.Sinks.NONE);
            assertEquals(List.of("pure_cue"), result.getCues());
        }

        @Test
        void aCueBesideGrantsStaysSilentWhenTheTableRolledNothing() {
            LootEngine.Sinks sinks = LootEngine.Sinks.builder().dropLists(id -> Map.of()).build();
            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(LootGrants.ofDropList("Empty"), "jackpot")),
                    null, FactorLookup.none(), () -> 0.0, sinks);
            assertTrue(result.getCues().isEmpty(),
                    "no fanfare over an empty hand - this is the whole point of the rule");
        }

        @Test
        void aCueBesideGrantsPlaysOnceThoseGrantsProducedSomething() {
            LootEngine.Sinks sinks = LootEngine.Sinks.builder()
                    .dropLists(id -> Map.of("Gem_Ruby", 1)).build();
            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(LootGrants.ofDropList("Rich"), "jackpot")),
                    null, FactorLookup.none(), () -> 0.0, sinks);
            assertEquals(List.of("jackpot"), result.getCues());
        }

        @Test
        void bothAltitudesAreJudgedSeparatelyAndBothCanPlay() {
            RecordingItems items = new RecordingItems();
            Roll roll = Roll.of(null, null, null,
                    Roll.Ladder.of(null, new Roll.Ladder.Floor[] {
                            Roll.Ladder.Floor.of(0.0, LootGrants.ofItem("Tier", 1), "tier_cue")}),
                    LootGrants.ofItem("Base", 1), "hit_cue");

            LootEngine.Result result = LootEngine.rollAndGrant(List.of(roll), null, FactorLookup.none(),
                    () -> 0.0, itemsOnly(items));

            assertEquals(List.of("hit_cue", "tier_cue"), result.getCues(),
                    "the roll cue comes first, then the floor cue");
        }

        @Test
        void aFloorCueIsSilencedByItsOwnFloorEvenWhenTheRollProduced() {
            LootEngine.Sinks sinks = LootEngine.Sinks.builder()
                    .items((id, count) -> count)
                    .dropLists(id -> Map.of())
                    .build();
            Roll roll = Roll.of(null, null, null,
                    Roll.Ladder.of(null, new Roll.Ladder.Floor[] {
                            Roll.Ladder.Floor.of(0.0, LootGrants.ofDropList("Empty"), "tier_cue")}),
                    LootGrants.ofItem("Base", 1), "hit_cue");

            LootEngine.Result result = LootEngine.rollAndGrant(List.of(roll), null, FactorLookup.none(),
                    () -> 0.0, sinks);

            assertEquals(List.of("hit_cue"), result.getCues(),
                    "each cue is judged against ITS OWN grants, never the other altitude's");
        }
    }

    // ==================== registered rewards ====================

    @Nested
    class Rewards {

        @Test
        void aRegisteredKindIsPaidOutAndCountsAsProduced() {
            List<RewardSpec> paid = new ArrayList<>();
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.register("currency", "test", (spec, subject) -> paid.add(spec));

            LootGrants grants = LootGrants.of(null, null, null, new LootGrants.Reward[] {
                    LootGrants.Reward.of("currency", Map.of("id", "token", "amount", "25"))});

            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(grants, "cue")), null, FactorLookup.none(), () -> 0.0,
                    LootEngine.Sinks.builder().rewards(kinds, subject()).build());

            assertEquals(1, paid.size());
            assertEquals("25", paid.get(0).param("amount"));
            assertEquals(1, result.getRewardsPaid());
            assertEquals(List.of("cue"), result.getCues());
        }

        @Test
        void anUnregisteredKindPaysNothingAndIsCountedAsLost() {
            List<String> warnings = new ArrayList<>();
            LootGrants grants = LootGrants.of(null, null, null, new LootGrants.Reward[] {
                    LootGrants.Reward.of("absentmod:mana", Map.of("amount", "10"))});

            LootEngine.Result result = LootEngine.rollAndGrant(
                    List.of(alwaysGranting(grants, "cue")), null, FactorLookup.none(), () -> 0.0,
                    LootEngine.Sinks.builder()
                            .rewards(new RewardKindRegistry("test"), subject())
                            .warn(warnings::add)
                            .build());

            assertEquals(0, result.getRewardsPaid());
            assertEquals(1, result.getRewardsLost());
            assertTrue(result.getCues().isEmpty(), "nothing was produced, so nothing celebrates");
            assertEquals(1, warnings.size());
        }

        static Subject subject() {
            return Subject.of(java.util.UUID.nameUUIDFromBytes("tester".getBytes()), "Tester");
        }
    }

    // ==================== commands ====================

    @Test
    void commandsRunThroughTheDispatcherWithPlaceholdersSubstituted() {
        List<String> ran = new ArrayList<>();
        LootGrants grants = LootGrants.of(null, null,
                new String[] {"give {player} Coin_Gold 5", "  "}, null);

        LootEngine.Result result = LootEngine.rollAndGrant(List.of(alwaysGranting(grants, null)),
                null, FactorLookup.none(), () -> 0.0,
                LootEngine.Sinks.builder().commands(ran::add, Map.of("player", "Tester")).build());

        assertEquals(List.of("give Tester Coin_Gold --quantity=5"), ran,
                "a positional give count is rewritten, since the engine ignores it");
        assertEquals(1, result.getCommandsRun());
    }

    // ==================== trigger filtering ====================

    @Test
    void onlyRollsAnsweringTheAskedTriggerFire() {
        RecordingItems items = new RecordingItems();
        List<Roll> rolls = List.of(
                Roll.of("Cycle", null, null, null, LootGrants.ofItem("Cycle_Item", 1), null),
                Roll.of("Completion", null, null, null, LootGrants.ofItem("End_Item", 1), null));

        LootEngine.rollAndGrant(rolls, "Completion", FactorLookup.none(), () -> 0.0, itemsOnly(items));

        assertEquals(List.of("End_Item x1"), items.asked);
    }

    @Test
    void aGatedRollIsSkippedWithoutStoppingTheOnesAfterIt() {
        RecordingItems items = new RecordingItems();
        List<Roll> rolls = List.of(
                Roll.of(null, null, FactorFormula.of(0.0, null, null), null,
                        LootGrants.ofItem("Never", 1), null),
                alwaysGranting(LootGrants.ofItem("Always", 1), null));

        LootEngine.rollAndGrant(rolls, null, FactorLookup.none(), () -> 0.0, itemsOnly(items));

        assertEquals(List.of("Always x1"), items.asked);
    }
}
