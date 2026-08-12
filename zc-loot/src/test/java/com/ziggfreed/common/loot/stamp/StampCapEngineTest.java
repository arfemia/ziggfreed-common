package com.ziggfreed.common.loot.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.FactorLookup;

/**
 * The stamp decision: what gets drawn, what it rolls to, and what the ceilings do to it.
 *
 * <p>The cases that matter most are the ceiling ones, because a wrong answer there is either an
 * item that grows without limit or a player charged for a stamp that could never have landed.
 */
class StampCapEngineTest {

    static DoubleSupplier samples(double... values) {
        int[] at = {0};
        return () -> values[Math.min(at[0]++, values.length - 1)];
    }

    static FactorLookup lookup(String factorId, double value) {
        Map<String, Double> values = new HashMap<>();
        values.put(factorId, value);
        return (id, param) -> values.get(id);
    }

    static StatRollEntry entry(String stat, double min, double max, Double weight, Boolean always) {
        return StatRollEntry.of(stat, StatRollEntry.Points.of(min, max, null), weight, always);
    }

    static StampSpec spec(StatRollEntry[] entries, StampSpec.Picks picks, Boolean unique,
            StampSpec.Caps caps) {
        return StampSpec.of(null, entries, picks, unique, caps);
    }

    // ==================== choosing ====================

    @Nested
    class Choosing {

        @Test
        void withNoPicksOnlyTheAlwaysEntriesLand() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {
                            entry("Damage", 5, 5, null, true),
                            entry("Speed", 3, 3, null, null)}, null, null, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.5));

            assertEquals(List.of(new StatRoll("Damage", 5)), plan.entries(),
                    "an absent Picks must be inert rather than quietly handing out a free stat");
        }

        @Test
        void anAlwaysEntryCostsNoPickAndLandsBesideTheLotteryDraw() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {
                            entry("Durability", 1, 1, null, true),
                            entry("Damage", 4, 4, null, null)},
                            StampSpec.Picks.of(1, 1), null, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.5));

            assertEquals(2, plan.entries().size());
            assertEquals("Durability", plan.entries().get(0).statId());
            assertEquals("Damage", plan.entries().get(1).statId());
        }

        @Test
        void uniqueNeverDrawsTheSameStatTwice() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {
                            entry("Damage", 2, 2, null, null),
                            entry("Speed", 2, 2, null, null)},
                            StampSpec.Picks.of(2, 2), true, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.1, 0.9));

            assertEquals(2, plan.entries().size());
            assertFalse(plan.entries().get(0).statId().equals(plan.entries().get(1).statId()));
        }

        @Test
        void aZeroWeightEntryIsParkedRatherThanDrawn() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {
                            StatRollEntry.of("Parked", StatRollEntry.Points.of(9.0, 9.0, null), 0.0, null),
                            entry("Live", 3, 3, 5.0, null)},
                            StampSpec.Picks.of(1, 1), null, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.0));

            assertEquals(List.of(new StatRoll("Live", 3)), plan.entries());
        }

        @Test
        void anEntryWithNoStatIdIsDroppedBeforeAnythingElse() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {entry(null, 5, 5, null, true)}, null, null, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.5));
            assertEquals(StampPlan.NOTHING, plan);
        }
    }

    // ==================== rolling ====================

    @Nested
    class Rolling {

        @Test
        void aRangeRollsBetweenItsBounds() {
            StampPlan low = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {entry("Damage", 2, 10, null, true)}, null, null, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.0));
            StampPlan high = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {entry("Damage", 2, 10, null, true)}, null, null, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.999));

            assertEquals(2, low.entries().get(0).points());
            assertEquals(10, high.entries().get(0).points());
        }

        @Test
        void factorTermsAddOntoTheRolledValue() {
            StatRollEntry scaled = StatRollEntry.of("Damage",
                    StatRollEntry.Points.of(2.0, 2.0,
                            new FactorFormula.Term[] {FactorFormula.Term.of("mymod:skill", null, 0.5)}),
                    null, true);

            StampPlan plan = StampCapEngine.resolve(spec(new StatRollEntry[] {scaled}, null, null, null),
                    StampInspection.empty(), lookup("mymod:skill", 10.0), samples(0.0));

            assertEquals(7, plan.entries().get(0).points(), "2 plus 10 at weight 0.5");
        }

        @Test
        void anEntryThatRoundsToNothingIsDroppedRatherThanWrittenAsZero() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {entry("Damage", 0.2, 0.2, null, true)}, null, null, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.0));
            assertEquals(StampPlan.NOTHING, plan);
        }
    }

    // ==================== ceilings ====================

    @Nested
    class Ceilings {

        static StampSpec.Caps budgets(StampSpec.Budget... entries) {
            return StampSpec.Caps.of(entries, null);
        }

        @Test
        void theLowestBudgetIsTheOneThatBinds() {
            StampSpec.Caps caps = budgets(StampSpec.Budget.flat(40.0),
                    StampSpec.Budget.scaled(4.0,
                            new FactorFormula.Term[] {FactorFormula.Term.of("mymod:tier", null, null)}));

            Double effective = StampCapEngine.effectiveBudget(caps, lookup("mymod:tier", 2.0));
            assertEquals(8.0, effective, 1e-9, "8 is tighter than 40 right now, so 8 binds");

            assertEquals(40.0, StampCapEngine.effectiveBudget(caps, lookup("mymod:tier", 1000.0)), 1e-9,
                    "the hard ceiling takes over once the earned allowance passes it");
        }

        @Test
        void anEntryWithNeitherRouteSaysNothingAndIsSkipped() {
            StampSpec.Caps caps = budgets(StampSpec.Budget.flat(null), StampSpec.Budget.flat(25.0));
            assertFalse(StampSpec.Budget.flat(null).hasExactlyOneRoute());
            assertEquals(25.0, StampCapEngine.effectiveBudget(caps, FactorLookup.none()), 1e-9,
                    "the unusable entry must not drag the ceiling down to nothing");
        }

        @Test
        void noBudgetsAuthoredMeansNoTotalCeiling() {
            assertEquals(null, StampCapEngine.effectiveBudget(null, FactorLookup.none()));
            assertEquals(null, StampCapEngine.effectiveBudget(StampSpec.Caps.of(null, null),
                    FactorLookup.none()));
        }

        @Test
        void theBudgetIsMeasuredAgainstWhatTheItemAlreadyCarries() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {entry("Damage", 10, 10, null, true)}, null, null,
                            budgets(StampSpec.Budget.flat(40.0))),
                    new StampInspection(38, Map.of("Damage", 38), 3), FactorLookup.none(), samples(0.0));

            assertEquals(List.of(new StatRoll("Damage", 2)), plan.entries(),
                    "an item at 38 of 40 has two points left, not forty");
        }

        @Test
        void theBudgetIsSpentOnceAcrossEveryEntryInThePlan() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {
                            entry("Damage", 8, 8, null, true),
                            entry("Speed", 8, 8, null, true)}, null, null,
                            budgets(StampSpec.Budget.flat(10.0))),
                    StampInspection.empty(), FactorLookup.none(), samples(0.0));

            assertEquals(10, plan.totalPoints(), "the second entry gets what the first left, not a fresh 10");
        }

        @Test
        void aPerStatCeilingBindsSeparatelyFromTheTotal() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {
                            entry("Damage", 20, 20, null, true),
                            entry("Speed", 5, 5, null, true)}, null, null,
                            StampSpec.Caps.of(new StampSpec.Budget[] {StampSpec.Budget.flat(100.0)},
                                    Map.of("Damage", 12.0))),
                    StampInspection.empty(), FactorLookup.none(), samples(0.0));

            assertEquals(List.of(new StatRoll("Damage", 12), new StatRoll("Speed", 5)), plan.entries(),
                    "a lucky streak cannot pile everything into one stat");
        }

        @Test
        void aFullItemIsDeniedRatherThanStampingNothingForFree() {
            StampPlan plan = StampCapEngine.resolve(
                    spec(new StatRollEntry[] {entry("Damage", 5, 5, null, true)}, null, null,
                            budgets(StampSpec.Budget.flat(40.0))),
                    new StampInspection(40, Map.of("Damage", 40), 6), FactorLookup.none(), samples(0.0));

            assertTrue(plan.denied(), "the caller must be able to abort before charging");
            assertFalse(plan.hasEntries());
        }

        @Test
        void aMissTellsTheCallerSomethingDifferentFromADenial() {
            StampPlan miss = StampCapEngine.resolve(spec(new StatRollEntry[0], null, null, null),
                    StampInspection.empty(), FactorLookup.none(), samples(0.0));
            assertFalse(miss.denied(), "no candidates is a legitimate miss, not a refusal");
        }
    }

    // ==================== the write boundary ====================

    @Nested
    class TheStamper {

        /** A stamper that records what it was told to write and pretends an item is a point tally. */
        static final class FakeStamper {
            final List<StatRoll> written = new ArrayList<>();
            final Map<String, Integer> carried = new HashMap<>();

            StampInspection inspect() {
                int total = 0;
                for (int points : carried.values()) {
                    total += points;
                }
                return new StampInspection(total, Map.copyOf(carried), written.isEmpty() ? 0 : 1);
            }

            void apply(List<StatRoll> entries) {
                written.addAll(entries);
                for (StatRoll roll : entries) {
                    carried.merge(roll.statId(), roll.points(), Integer::sum);
                }
            }
        }

        @Test
        void reStampingAnItemSeesWhatTheEarlierStampLeft() {
            FakeStamper stamper = new FakeStamper();
            StampSpec spec = spec(new StatRollEntry[] {entry("Damage", 6, 6, null, true)}, null, null,
                    StampSpec.Caps.of(new StampSpec.Budget[] {StampSpec.Budget.flat(10.0)}, null));

            StampPlan first = StampCapEngine.resolve(spec, stamper.inspect(), FactorLookup.none(), samples(0.0));
            stamper.apply(first.entries());
            StampPlan second = StampCapEngine.resolve(spec, stamper.inspect(), FactorLookup.none(), samples(0.0));
            stamper.apply(second.entries());

            assertEquals(6, first.totalPoints());
            assertEquals(4, second.totalPoints(), "the budget carried over between stamps");
            assertEquals(10, stamper.carried.get("Damage"));
        }

        @Test
        void aFullyCappedReStampIsDeniedSoNothingIsCharged() {
            FakeStamper stamper = new FakeStamper();
            stamper.carried.put("Damage", 10);
            StampSpec spec = spec(new StatRollEntry[] {entry("Damage", 6, 6, null, true)}, null, null,
                    StampSpec.Caps.of(new StampSpec.Budget[] {StampSpec.Budget.flat(10.0)}, null));

            StampPlan plan = StampCapEngine.resolve(spec, stamper.inspect(), FactorLookup.none(), samples(0.0));

            assertTrue(plan.denied());
            assertTrue(stamper.written.isEmpty());
        }
    }
}
