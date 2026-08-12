package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * The decision table for one roll: what shuts the gate, what the chance actually works out to, which
 * ladder floor wins, and what stacks with what.
 */
class RollEvaluatorTest {

    /** A lookup over a fixture map; any id not in it is unanswerable. */
    static FactorLookup lookup(Map<String, Double> values) {
        return (factorId, param) -> values.get(param == null ? factorId : factorId + ' ' + param);
    }

    static FactorLookup lookup(String factorId, double value) {
        Map<String, Double> values = new HashMap<>();
        values.put(factorId, value);
        return lookup(values);
    }

    static DoubleSupplier sample(double value) {
        return () -> value;
    }

    static FactorFormula.Term term(String factorId, Double weight) {
        return FactorFormula.Term.of(factorId, null, weight);
    }

    // ==================== conditions ====================

    @Nested
    class Conditions {

        @Test
        void aFailedConditionMeansNothingFiresAndNoSampleIsDrawn() {
            int[] draws = {0};
            Roll roll = Roll.of(null,
                    new FactorCondition[] {FactorCondition.of("mymod:quality", null, 5.0, null)},
                    FactorFormula.of(100.0, null, null), null, LootGrants.ofItem("Coin", 1), null);

            RollEvaluator.Outcome outcome = RollEvaluator.evaluate(roll, lookup("mymod:quality", 2.0),
                    () -> {
                        draws[0]++;
                        return 0.0;
                    });

            assertFalse(outcome.isHit());
            assertEquals(0, draws[0], "a gated-out roll must not consume a chance sample");
        }

        @Test
        void anUnanswerableFactorShutsTheGate() {
            Roll roll = Roll.of(null,
                    new FactorCondition[] {FactorCondition.of("absentmod:thing", null, null, null)},
                    null, null, LootGrants.ofItem("Coin", 1), null);
            assertFalse(RollEvaluator.evaluate(roll, FactorLookup.none(), sample(0.0)).isHit());
        }

        @Test
        void aConditionWithNoFactorIdIsSkippedRatherThanFailing() {
            Roll roll = Roll.of(null, new FactorCondition[] {FactorCondition.of(null, null, 5.0, null)},
                    null, null, LootGrants.ofItem("Coin", 1), null);
            assertTrue(RollEvaluator.evaluate(roll, FactorLookup.none(), sample(0.0)).isHit(),
                    "a half-authored line must not silently hide working content");
        }
    }

    // ==================== chance ====================

    @Nested
    class Chance {

        @Test
        void anAbsentChanceAlwaysFires() {
            assertTrue(RollEvaluator.chancePasses(null, FactorLookup.none(), sample(0.999)));
        }

        @Test
        void factorTermsAddOntoTheBaseInPercent() {
            FactorFormula chance = FactorFormula.of(10.0, new FactorFormula.Term[] {term("mymod:luck", 5.0)}, null);
            assertEquals(35.0, RollEvaluator.effectiveChancePercent(chance, lookup("mymod:luck", 5.0)), 1e-9);
        }

        @Test
        void theAuthoredCeilingHoldsAStackingBonusDown() {
            FactorFormula chance = FactorFormula.of(10.0,
                    new FactorFormula.Term[] {term("mymod:luck", 5.0)},
                    FactorFormula.Clamp.of(null, 90.0));
            assertEquals(90.0, RollEvaluator.effectiveChancePercent(chance, lookup("mymod:luck", 1000.0)), 1e-9);
        }

        @Test
        void aNegativeStackCannotPushTheChanceBelowNever() {
            FactorFormula chance = FactorFormula.of(10.0,
                    new FactorFormula.Term[] {term("mymod:curse", -5.0)}, null);
            assertEquals(0.0, RollEvaluator.effectiveChancePercent(chance, lookup("mymod:curse", 100.0)), 1e-9);
        }

        @Test
        void aZeroChanceShortCircuitsWithoutDrawingASample() {
            int[] draws = {0};
            boolean passed = RollEvaluator.chancePasses(FactorFormula.of(0.0, null, null),
                    FactorLookup.none(), () -> {
                        draws[0]++;
                        return 0.0;
                    });
            assertFalse(passed);
            assertEquals(0, draws[0]);
        }

        @Test
        void aCertainChanceAlsoSkipsTheSample() {
            int[] draws = {0};
            boolean passed = RollEvaluator.chancePasses(FactorFormula.of(100.0, null, null),
                    FactorLookup.none(), () -> {
                        draws[0]++;
                        return 0.99;
                    });
            assertTrue(passed);
            assertEquals(0, draws[0]);
        }

        @Test
        void theSampleIsComparedInPercentUnits() {
            FactorFormula chance = FactorFormula.of(25.0, null, null);
            assertTrue(RollEvaluator.chancePasses(chance, FactorLookup.none(), sample(0.2499)));
            assertFalse(RollEvaluator.chancePasses(chance, FactorLookup.none(), sample(0.25)));
        }

        @Test
        void anUnansweredTermCostsOnlyItsOwnContribution() {
            FactorFormula chance = FactorFormula.of(30.0,
                    new FactorFormula.Term[] {term("absentmod:bonus", 5.0)}, null);
            assertEquals(30.0, RollEvaluator.effectiveChancePercent(chance, FactorLookup.none()), 1e-9,
                    "an absent mod must cost its bonus, never the whole chance");
        }
    }

    // ==================== ladder ====================

    @Nested
    class Ladder {

        static Roll.Ladder ladder(Roll.Ladder.Floor... floors) {
            return Roll.Ladder.of(new FactorFormula.Term[] {term("mymod:luck", null)}, floors);
        }

        @Test
        void theHighestReachedFloorWins() {
            Roll.Ladder ladder = ladder(
                    Roll.Ladder.Floor.of(0.0, LootGrants.ofItem("Bronze", 1), null),
                    Roll.Ladder.Floor.of(50.0, LootGrants.ofItem("Silver", 1), null),
                    Roll.Ladder.Floor.of(100.0, LootGrants.ofItem("Gold", 1), null));

            assertEquals("Silver", firstItem(RollEvaluator.highestFloor(ladder, lookup("mymod:luck", 75.0))));
            assertEquals("Gold", firstItem(RollEvaluator.highestFloor(ladder, lookup("mymod:luck", 100.0))));
        }

        @Test
        void aZeroFloorIsReachableSoABaselineTierIsAuthorable() {
            Roll.Ladder ladder = ladder(Roll.Ladder.Floor.of(0.0, LootGrants.ofItem("Bronze", 1), null));
            assertEquals("Bronze", firstItem(RollEvaluator.highestFloor(ladder, lookup("mymod:luck", 0.0))));
        }

        @Test
        void anOmittedMinReadsAsZero() {
            Roll.Ladder ladder = ladder(Roll.Ladder.Floor.of(null, LootGrants.ofItem("Bronze", 1), null));
            assertEquals("Bronze", firstItem(RollEvaluator.highestFloor(ladder, lookup("mymod:luck", 0.0))));
        }

        @Test
        void twoFloorsAtTheSameThresholdResolveToTheLastWritten() {
            Roll.Ladder ladder = ladder(
                    Roll.Ladder.Floor.of(50.0, LootGrants.ofItem("First", 1), null),
                    Roll.Ladder.Floor.of(50.0, LootGrants.ofItem("Last", 1), null));
            assertEquals("Last", firstItem(RollEvaluator.highestFloor(ladder, lookup("mymod:luck", 60.0))));
        }

        @Test
        void theLadderValueIsDeliberatelyUncappedSoAStackCanReachAHighFloor() {
            Roll.Ladder ladder = ladder(Roll.Ladder.Floor.of(5000.0, LootGrants.ofItem("Mythic", 1), null));
            assertEquals("Mythic", firstItem(RollEvaluator.highestFloor(ladder, lookup("mymod:luck", 9001.0))));
        }

        @Test
        void nothingIsReachedBelowEveryThreshold() {
            Roll.Ladder ladder = ladder(Roll.Ladder.Floor.of(50.0, LootGrants.ofItem("Silver", 1), null));
            assertNull(RollEvaluator.highestFloor(ladder, lookup("mymod:luck", 10.0)));
        }

        @Test
        void anEmptyTermListResolvesToZeroRatherThanGoingDark() {
            Roll.Ladder ladder = Roll.Ladder.of(null,
                    new Roll.Ladder.Floor[] {Roll.Ladder.Floor.of(0.0, LootGrants.ofItem("Flat", 1), null)});
            assertEquals(0.0, RollEvaluator.ladderValue(ladder, FactorLookup.none()), 1e-9);
            assertEquals("Flat", firstItem(RollEvaluator.highestFloor(ladder, FactorLookup.none())));
        }

        @Test
        void aFailedChanceMeansTheLadderIsNeverEvaluated() {
            Roll roll = Roll.of(null, null, FactorFormula.of(0.0, null, null),
                    ladder(Roll.Ladder.Floor.of(0.0, LootGrants.ofItem("Bronze", 1), null)), null, null);
            RollEvaluator.Outcome outcome = RollEvaluator.evaluate(roll, lookup("mymod:luck", 999.0), sample(0.0));
            assertFalse(outcome.isHit());
            assertNull(outcome.getFloorGrants());
        }

        static String firstItem(Roll.Ladder.Floor floor) {
            assertNotNull(floor, "expected a floor to be reached");
            return floor.getGrants().itemsOrEmpty().get(0).getItem();
        }
    }

    // ==================== stacking ====================

    @Test
    void topLevelAndFloorGrantsBothApply() {
        Roll roll = Roll.of(null, null, null,
                Roll.Ladder.of(new FactorFormula.Term[] {term("mymod:luck", null)},
                        new Roll.Ladder.Floor[] {Roll.Ladder.Floor.of(10.0, LootGrants.ofItem("Bonus", 1), "tier")}),
                LootGrants.ofItem("Base", 1), "hit");

        RollEvaluator.Outcome outcome = RollEvaluator.evaluate(roll, lookup("mymod:luck", 20.0), sample(0.0));

        assertTrue(outcome.isHit());
        assertEquals("Base", outcome.getTopGrants().itemsOrEmpty().get(0).getItem());
        assertEquals("Bonus", outcome.getFloorGrants().itemsOrEmpty().get(0).getItem());
        assertEquals("hit", outcome.getTopCue());
        assertEquals("tier", outcome.getFloorCue());
    }

    @Test
    void aRollAnswersOnlyToItsOwnTriggerAndAnullTriggerAsksForEverything() {
        Roll cycle = Roll.of("Cycle", null, null, null, LootGrants.ofItem("Coin", 1), null);
        assertTrue(cycle.answersTo("cycle"));
        assertTrue(cycle.answersTo(null));
        assertFalse(cycle.answersTo("Completion"));

        Roll plain = Roll.of(null, null, null, null, LootGrants.ofItem("Coin", 1), null);
        assertEquals(Roll.DEFAULT_TRIGGER, plain.effectiveTrigger());
        assertTrue(plain.answersTo(Roll.DEFAULT_TRIGGER));
    }
}
