package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * The competing-outcome half of a table: how many picks a moment earns, which entries compete for
 * them, and what a draw hands over.
 *
 * <p>Every number here is a FIXTURE. The one case worth reading twice is the threshold: a bonus pick
 * per N points is authored as a weight of one over N, which no binary double holds exactly, so a
 * player sitting exactly on the line has to come out ahead rather than a hair short of the pick they
 * were promised.
 */
class LootPoolTest {

    static final String SCORE = "test:score";

    static FactorLookup scoreOf(double value) {
        return (factorId, param) -> SCORE.equalsIgnoreCase(factorId) ? value : null;
    }

    static LootPool.Entry entry(String itemId, double weight, FactorCondition... conditions) {
        return LootPool.Entry.of(weight, conditions.length == 0 ? null : conditions,
                LootGrants.ofItem(itemId, 1));
    }

    static FactorFormula picksPer(double base, double perPoint, Double ceiling) {
        return FactorFormula.of(base,
                new FactorFormula.Term[] {FactorFormula.Term.of(SCORE, null, perPoint)},
                ceiling == null ? null : FactorFormula.Clamp.of(null, ceiling));
    }

    static List<String> itemsOf(List<LootEngine.Selected> selected) {
        List<String> out = new ArrayList<>();
        for (LootEngine.Selected s : selected) {
            if (s.grants() != null) {
                s.grants().itemsOrEmpty().forEach(i -> out.add(i.getItem()));
            }
        }
        return out;
    }

    // ==================== how many picks ====================

    @Nested
    class PickCount {

        @Test
        void aPoolWithNoFormulaMakesOnePick() {
            LootPool pool = LootPool.of(null, new LootPool.Entry[] {entry("Common", 1)});
            assertEquals(LootPool.DEFAULT_PICKS, pool.pickCount(FactorLookup.none()));
        }

        @Test
        void aScoreOfExactlyOneThresholdEarnsTheBonusPick() {
            LootPool pool = LootPool.of(picksPer(1, 1.0 / 1200.0, null), new LootPool.Entry[] {entry("A", 1)});
            assertEquals(1, pool.pickCount(scoreOf(0)));
            assertEquals(1, pool.pickCount(scoreOf(1199)));
            assertEquals(2, pool.pickCount(scoreOf(1200)),
                    "one over a whole number cannot be held exactly, and the player must not lose the pick");
            assertEquals(3, pool.pickCount(scoreOf(2400)));
        }

        @Test
        void theClampIsTheCeilingHoweverGoodTheRun() {
            LootPool pool = LootPool.of(picksPer(1, 1.0 / 1200.0, 3.0), new LootPool.Entry[] {entry("A", 1)});
            assertEquals(3, pool.pickCount(scoreOf(1_000_000)));
        }

        @Test
        void anUnansweredFactorLeavesTheBaseAlone() {
            LootPool pool = LootPool.of(picksPer(2, 1.0 / 100.0, null), new LootPool.Entry[] {entry("A", 1)});
            assertEquals(2, pool.pickCount(FactorLookup.none()),
                    "a term that cannot resolve contributes nothing rather than voiding the count");
        }

        @Test
        void aFormulaThatWorksOutBelowOneMakesNoPickAtAll() {
            LootPool pool = LootPool.of(picksPer(0, 1.0 / 1000.0, null), new LootPool.Entry[] {entry("A", 1)});
            assertEquals(0, pool.pickCount(scoreOf(0)));
        }

        @Test
        void aRunawayFormulaStopsAtTheHardCeiling() {
            LootPool pool = LootPool.of(picksPer(0, 1000.0, null), new LootPool.Entry[] {entry("A", 1)});
            assertEquals(LootPool.MAX_PICKS, pool.pickCount(scoreOf(1_000_000)));
        }
    }

    // ==================== which entries compete ====================

    @Nested
    class Eligibility {

        @Test
        void anEntryOutOfReachIsNotInTheBagAtAll() {
            LootPool pool = LootPool.of(null, new LootPool.Entry[] {
                    entry("Common", 1),
                    entry("Premium", 1, FactorCondition.of(SCORE, null, 2000.0, null))});

            assertEquals(1, pool.eligible(scoreOf(0)).size());
            assertEquals(2, pool.eligible(scoreOf(2000)).size());
        }

        @Test
        void anUnanswerableGateKeepsAnEntryOut() {
            LootPool pool = LootPool.of(null, new LootPool.Entry[] {
                    entry("Gated", 1, FactorCondition.of("nobody:answers_this", null, 1.0, null))});
            assertTrue(pool.eligible(FactorLookup.none()).isEmpty());
        }

        @Test
        void anEntryWritingNoWeightStillCompetes() {
            LootPool.Entry plain = LootPool.Entry.of(null, null, LootGrants.ofItem("Common", 1));
            assertEquals(LootPool.Entry.DEFAULT_WEIGHT, plain.effectiveWeight());
        }

        @Test
        void aNegativeWeightIsNeverPickedRatherThanBendingTheOdds() {
            assertEquals(0.0, entry("Parked", -5).effectiveWeight());
        }
    }

    // ==================== drawing ====================

    @Nested
    class Drawing {

        @Test
        void everyPickHandsOverSomething() {
            LootPool pool = LootPool.of(picksPer(3, 0, null), new LootPool.Entry[] {entry("Common", 1)});
            Random rng = new Random(5);
            List<LootEngine.Selected> decided =
                    LootEngine.select(List.of(), List.of(pool), null, FactorLookup.none(), rng::nextDouble);
            assertEquals(List.of("Common", "Common", "Common"), itemsOf(decided));
        }

        @Test
        void picksAreDrawnWithReplacementSoOneEntryCanComeUpTwice() {
            LootPool pool = LootPool.of(picksPer(4, 0, null), new LootPool.Entry[] {
                    entry("Weighted", 99), entry("Rare", 1)});
            Random rng = new Random(11);
            List<String> drawn = itemsOf(LootEngine.select(List.of(), List.of(pool), null,
                    FactorLookup.none(), rng::nextDouble));
            assertEquals(4, drawn.size());
            assertTrue(drawn.stream().filter("Weighted"::equals).count() > 1);
        }

        @Test
        void aFixedSeedDrawsTheSameThingTwice() {
            LootPool pool = LootPool.of(picksPer(3, 0, null), new LootPool.Entry[] {
                    entry("A", 10), entry("B", 5), entry("C", 1)});
            Random first = new Random(20260812L);
            Random second = new Random(20260812L);
            assertEquals(
                    itemsOf(LootEngine.select(List.of(), List.of(pool), null, FactorLookup.none(),
                            first::nextDouble)),
                    itemsOf(LootEngine.select(List.of(), List.of(pool), null, FactorLookup.none(),
                            second::nextDouble)));
        }

        @Test
        void twoReferencedPoolsEachDrawTheirOwnRatherThanSharingOneBag() {
            LootPool one = LootPool.of(picksPer(1, 0, null), new LootPool.Entry[] {entry("FromOne", 1)});
            LootPool two = LootPool.of(picksPer(1, 0, null), new LootPool.Entry[] {entry("FromTwo", 1)});
            Random rng = new Random(3);
            assertEquals(List.of("FromOne", "FromTwo"), itemsOf(LootEngine.select(List.of(),
                    List.of(one, two), null, FactorLookup.none(), rng::nextDouble)));
        }

        @Test
        void aPoolIsDrawnOnlyOnTheSitesPlainMoment() {
            LootPool pool = LootPool.of(picksPer(1, 0, null), new LootPool.Entry[] {entry("Common", 1)});
            Random rng = new Random(1);
            assertTrue(LootEngine.select(List.of(), List.of(pool), "Completion", FactorLookup.none(),
                    rng::nextDouble).isEmpty());
            assertFalse(LootEngine.select(List.of(), List.of(pool), Roll.DEFAULT_TRIGGER,
                    FactorLookup.none(), rng::nextDouble).isEmpty());
        }
    }

    // ==================== varying quantities ====================

    @Nested
    class VaryingQuantities {

        @Test
        void aQuantityWithNoTopIsExactlyItsCount() {
            LootGrants.Item fixed = LootGrants.Item.of("Coin_Gold", 3);
            assertFalse(fixed.varies());
            assertEquals(3, fixed.drawCount(() -> 0.99));
        }

        @Test
        void aRangeIsDrawnEvenlyBetweenItsEnds() {
            LootGrants.Item ranged = LootGrants.Item.of("Coin_Gold", 2, 4);
            assertTrue(ranged.varies());
            assertEquals(2, ranged.drawCount(() -> 0.0));
            assertEquals(3, ranged.drawCount(() -> 0.5));
            assertEquals(4, ranged.drawCount(() -> 0.999999));
        }

        @Test
        void aTopBelowTheCountIsIgnoredRatherThanInverted() {
            LootGrants.Item backwards = LootGrants.Item.of("Coin_Gold", 5, 2);
            assertFalse(backwards.varies());
            assertEquals(5, backwards.drawCount(() -> 0.5));
        }

        @Test
        void theQuantityIsDrawnWhenTheGrantIsDecidedNotWhenItLands() {
            LootGrants ranged = LootGrants.of(
                    new LootGrants.Item[] {LootGrants.Item.of("Ore", 10, 20)}, null, null, null);
            Random rng = new Random(77);
            List<LootEngine.Selected> decided = LootEngine.select(
                    List.of(Roll.of(null, null, null, null, ranged, null)), List.of(), null,
                    FactorLookup.none(), rng::nextDouble);

            assertEquals(1, decided.size());
            LootGrants.Item drawn = decided.get(0).grants().itemsOrEmpty().get(0);
            assertNotNull(drawn.getCount());
            assertTrue(drawn.getCount() >= 10 && drawn.getCount() <= 20);
            assertFalse(drawn.varies(), "the decided payout is a concrete number, not a range");
        }

        @Test
        void aGroupWithNothingVaryingIsHandedBackUntouched() {
            LootGrants fixed = LootGrants.ofItem("Coin_Gold", 3);
            assertEquals(fixed, fixed.drawQuantities(() -> 0.5));
        }
    }

    // ==================== the whole pass ====================

    @Test
    void rollsAndPoolsBothPayOutInOnePass() {
        Roll guaranteed = Roll.of(null, null, null, null, LootGrants.ofItem("Baseline", 1), null);
        LootPool pool = LootPool.of(picksPer(2, 0, null), new LootPool.Entry[] {entry("Drawn", 1)});
        Random rng = new Random(8);

        LootEngineTest.RecordingItems items = new LootEngineTest.RecordingItems();
        LootEngine.Result result = LootEngine.rollAndGrant(List.of(guaranteed), List.of(pool), null,
                FactorLookup.none(), rng::nextDouble, LootEngineTest.itemsOnly(items));

        assertEquals(Map.of("Baseline", 1, "Drawn", 2), result.getItems());
    }
}
