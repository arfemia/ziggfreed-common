package com.ziggfreed.common.rotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.util.PeriodMath;

/** The deterministic rotation primitive: the cadence, the seed, the draw, and the reroll layering. */
class RotationEngineTest {

    /** A candidate is an id, a grade and a weight. Nothing else is needed to draw one. */
    private record Candidate(String id, String tier, double weight) {
    }

    private static final Function<Candidate, String> ID = Candidate::id;
    private static final ToDoubleFunction<Candidate> WEIGHT = Candidate::weight;
    private static final WeightedSlotDraw.SlotMatcher<Candidate> MATCHER =
            (candidate, slot) -> slot.accepts(candidate.tier(), null);

    private static List<Candidate> pool() {
        List<Candidate> pool = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            pool.add(new Candidate("easy_" + i, "Easy", 1.0));
        }
        for (int i = 1; i <= 6; i++) {
            pool.add(new Candidate("hard_" + i, "Hard", 1.0));
        }
        return pool;
    }

    // ==================== Cadence ====================

    @Test
    @DisplayName("a daily cadence is one day long and turns over on the epoch day grid")
    void dailyCadence() {
        RotationSpec daily = RotationSpec.daily();
        assertEquals(PeriodMath.DAY_MS, daily.periodLengthMs());
        assertEquals(0L, daily.periodIndex(0L));
        assertEquals(1L, daily.periodIndex(PeriodMath.DAY_MS));
        assertTrue(daily.samePeriod(1L, PeriodMath.DAY_MS - 1));
        assertFalse(daily.samePeriod(PeriodMath.DAY_MS - 1, PeriodMath.DAY_MS));
    }

    @Test
    @DisplayName("a weekly cadence is one week long and starts on a Monday")
    void weeklyCadence() {
        RotationSpec weekly = RotationSpec.weekly();
        assertEquals(PeriodMath.WEEK_MS, weekly.periodLengthMs());
        // The epoch was a Thursday, so the week containing it began three days earlier.
        assertEquals(-3 * PeriodMath.DAY_MS, weekly.periodStartMs(0L));
    }

    @Test
    @DisplayName("an interval cadence takes its length from the span it was built with")
    void intervalCadence() {
        RotationSpec every2h = RotationSpec.every(2 * PeriodMath.HOUR_MS);
        assertEquals(2 * PeriodMath.HOUR_MS, every2h.periodLengthMs());
        assertEquals(0L, every2h.periodIndex(0L));
        assertEquals(1L, every2h.periodIndex(2 * PeriodMath.HOUR_MS));
    }

    @Test
    @DisplayName("an offset moves every boundary later, without changing how long a period is")
    void offsetMovesBoundariesLater() {
        RotationSpec fourAm = RotationSpec.daily().withOffsetMinutes(240);
        assertEquals(PeriodMath.DAY_MS, fourAm.periodLengthMs());
        assertEquals(4 * PeriodMath.HOUR_MS, fourAm.periodStartMs(5 * PeriodMath.HOUR_MS));
        assertEquals(-20 * PeriodMath.HOUR_MS, fourAm.periodStartMs(0L));
    }

    @Test
    @DisplayName("a countdown never reads zero, so a board on a boundary shows a whole period")
    void countdownIsNeverZero() {
        RotationSpec daily = RotationSpec.daily();
        assertEquals(PeriodMath.DAY_MS, daily.millisUntilNext(0L));
        assertTrue(daily.nextRotationMs(5 * PeriodMath.DAY_MS) > 5 * PeriodMath.DAY_MS);
    }

    // ==================== Seed ====================

    @Test
    @DisplayName("the same pool and period seed identically; a different period does not")
    void seedIsStablePerPeriod() {
        assertEquals(PoolSeed.mix("Daily", 100L, 0), PoolSeed.mix("Daily", 100L, 0));
        assertNotEquals(PoolSeed.mix("Daily", 100L, 0), PoolSeed.mix("Daily", 101L, 0));
        assertNotEquals(PoolSeed.mix("Daily", 100L, 0), PoolSeed.mix("Weekly", 100L, 0));
    }

    @Test
    @DisplayName("a per-position reroll seed can never collide with the base draw's")
    void perPositionSeedIsDistinct() {
        long base = PoolSeed.mix("Daily", 7L, 0);
        assertNotEquals(base, PoolSeed.mix("Daily", 7L, 0, 1));
        assertNotEquals(PoolSeed.mix("Daily", 7L, 0, 1), PoolSeed.mix("Daily", 7L, 0, 2));
        assertNotEquals(PoolSeed.mix("Daily", 7L, 0, 1), PoolSeed.mix("Daily", 7L, 1, 1));
    }

    // ==================== Draw ====================

    @Test
    @DisplayName("the same seed draws the same set, whatever order the pool arrived in")
    void drawIsReproducible() {
        List<PoolSlot> slots = List.of(PoolSlot.tier("Easy", 2), PoolSlot.tier("Hard", 1));
        long seed = PoolSeed.mix("Daily", 42L, 0);

        List<Candidate> first = WeightedSlotDraw.draw(pool(), slots, ID, WEIGHT, MATCHER, seed, 5);

        List<Candidate> shuffled = new ArrayList<>(pool());
        Collections.reverse(shuffled);
        List<Candidate> second = WeightedSlotDraw.draw(shuffled, slots, ID, WEIGHT, MATCHER, seed, 5);

        assertEquals(first.stream().map(Candidate::id).toList(),
                second.stream().map(Candidate::id).toList());
    }

    @Test
    @DisplayName("a different period draws a different set, which is what rotating means")
    void aNewPeriodRotates() {
        List<PoolSlot> slots = List.of(PoolSlot.tier("Easy", 2));
        List<String> today = WeightedSlotDraw.draw(pool(), slots, ID, WEIGHT, MATCHER,
                PoolSeed.mix("Daily", 1L, 0), 5).stream().map(Candidate::id).toList();
        List<String> tomorrow = WeightedSlotDraw.draw(pool(), slots, ID, WEIGHT, MATCHER,
                PoolSeed.mix("Daily", 2L, 0), 5).stream().map(Candidate::id).toList();
        assertNotEquals(today, tomorrow);
    }

    @Test
    @DisplayName("every position knows which slot produced it, and slots draw distinct candidates")
    void slotsFillDistinctPositions() {
        List<PoolSlot> slots = List.of(PoolSlot.tier("Easy", 2), PoolSlot.tier("Hard", 1));
        WeightedSlotDraw.DrawResult<Candidate> drawn = WeightedSlotDraw.drawDetailed(
                pool(), slots, ID, WEIGHT, MATCHER, PoolSeed.mix("Daily", 3L, 0), 5);

        assertEquals(3, drawn.size());
        assertEquals(3, drawn.slotByPosition().size());
        assertEquals("Easy", drawn.slotAt(0).tier());
        assertEquals("Hard", drawn.slotAt(2).tier());
        assertEquals(3, drawn.items().stream().map(Candidate::id).distinct().count());
    }

    @Test
    @DisplayName("an unfillable slot leaves its positions empty rather than borrowing another grade")
    void anUnfillableSlotDrawsNothing() {
        List<Candidate> onlyEasy = List.of(new Candidate("easy_1", "Easy", 1.0));
        List<PoolSlot> slots = List.of(PoolSlot.tier("Hard", 2));
        assertTrue(WeightedSlotDraw.draw(onlyEasy, slots, ID, WEIGHT, MATCHER, 1L, 5).isEmpty());
    }

    @Test
    @DisplayName("with no slots at all, the default count is drawn from the whole pool")
    void noSlotsDrawsTheDefaultCount() {
        assertEquals(4, WeightedSlotDraw.draw(pool(), List.of(), ID, WEIGHT, MATCHER, 9L, 4).size());
    }

    @Test
    @DisplayName("weight biases the draw")
    void weightBiasesTheDraw() {
        List<Candidate> weighted = List.of(
                new Candidate("common", "Easy", 100.0),
                new Candidate("rare", "Easy", 1.0));
        int commonWins = 0;
        for (int seed = 0; seed < 200; seed++) {
            List<Candidate> drawn = WeightedSlotDraw.draw(weighted, List.of(PoolSlot.tier("Easy", 1)),
                    ID, WEIGHT, MATCHER, seed, 1);
            if ("common".equals(drawn.get(0).id())) {
                commonWins++;
            }
        }
        assertTrue(commonWins > 150, "a hundredfold weight should dominate, saw " + commonWins);
    }

    @Test
    @DisplayName("a low weight never excludes a candidate; it is still the only one that can be drawn")
    void weightNeverExcludes() {
        List<Candidate> onlyRare = List.of(new Candidate("rare", "Easy", 0.001));
        List<Candidate> drawn = WeightedSlotDraw.draw(onlyRare, List.of(PoolSlot.tier("Easy", 1)),
                ID, WEIGHT, MATCHER, 7L, 1);
        assertEquals(1, drawn.size());
        assertEquals("rare", drawn.get(0).id());
    }

    @Test
    @DisplayName("an unweighted candidate is an ordinary candidate rather than an impossible one")
    void aZeroWeightReadsAsOne() {
        List<Candidate> zeroWeighted = List.of(new Candidate("zero", "Easy", 0.0));
        assertEquals("zero", WeightedSlotDraw.draw(zeroWeighted, List.of(PoolSlot.tier("Easy", 1)),
                ID, WEIGHT, MATCHER, 3L, 1).get(0).id());
    }

    @Test
    @DisplayName("the All strategy shows everything eligible, in id order, ignoring the seed")
    void allStrategyShowsEverything() {
        SelectionStrategy all = SelectionStrategies.get(SelectionSpec.TYPE_ALL);
        assertNotNull(all);
        WeightedSlotDraw.DrawResult<Candidate> drawn =
                all.draw(pool(), List.of(), ID, WEIGHT, MATCHER, 123L, 2);
        assertEquals(12, drawn.size());
        assertEquals("easy_1", drawn.items().get(0).id());
    }

    @Test
    @DisplayName("a selection type nobody registered resolves to nothing rather than the default")
    void anUnknownSelectionTypeResolvesToNothing() {
        assertNull(SelectionStrategies.get("Nobody_Registered_This"));
        assertTrue(SelectionStrategies.isRegistered("weighted_random"), "ids match case-insensitively");
        assertNotNull(SelectionStrategies.forSpec(null), "an unauthored selection is the weighted draw");
    }

    // ==================== Reroll layering ====================

    @Test
    @DisplayName("an override replaces its position and leaves every other one alone")
    void anOverrideReplacesOnePosition() {
        List<PoolSlot> slots = List.of(PoolSlot.tier("Easy", 3));
        WeightedSlotDraw.DrawResult<Candidate> base = WeightedSlotDraw.drawDetailed(
                pool(), slots, ID, WEIGHT, MATCHER, 5L, 5);

        Candidate swappedIn = new Candidate("easy_6", "Easy", 1.0);
        List<Candidate> shown = SlotRerollEngine.applyOverrides(base, Map.of(1, "easy_6"),
                id -> "easy_6".equals(id) ? swappedIn : null, MATCHER);

        assertEquals("easy_6", shown.get(1).id());
        assertEquals(base.items().get(0).id(), shown.get(0).id());
        assertEquals(base.items().get(2).id(), shown.get(2).id());
    }

    @Test
    @DisplayName("an override whose candidate no longer resolves is dropped, not shown")
    void aStaleOverrideIsDropped() {
        WeightedSlotDraw.DrawResult<Candidate> base = WeightedSlotDraw.drawDetailed(
                pool(), List.of(PoolSlot.tier("Easy", 2)), ID, WEIGHT, MATCHER, 5L, 5);
        List<Candidate> shown = SlotRerollEngine.applyOverrides(base, Map.of(0, "retired_bounty"),
                id -> null, MATCHER);
        assertEquals(base.items().get(0).id(), shown.get(0).id());
    }

    @Test
    @DisplayName("an override that no longer fits its slot is dropped, not shown")
    void anIneligibleOverrideIsDropped() {
        WeightedSlotDraw.DrawResult<Candidate> base = WeightedSlotDraw.drawDetailed(
                pool(), List.of(PoolSlot.tier("Easy", 2)), ID, WEIGHT, MATCHER, 5L, 5);
        Candidate wrongGrade = new Candidate("hard_1", "Hard", 1.0);
        List<Candidate> shown = SlotRerollEngine.applyOverrides(base, Map.of(0, "hard_1"),
                id -> wrongGrade, MATCHER);
        assertEquals(base.items().get(0).id(), shown.get(0).id());
    }

    @Test
    @DisplayName("a reroll excludes everything on show, INCLUDING the pick it is replacing")
    void rerollExcludesTheCurrentPickToo() {
        List<Candidate> shown = List.of(
                new Candidate("easy_1", "Easy", 1.0),
                new Candidate("easy_2", "Easy", 1.0));
        Set<String> excluded = SlotRerollEngine.excludeAll(shown, ID);
        assertTrue(excluded.contains("easy_1"));
        assertTrue(excluded.contains("easy_2"));
    }

    @Test
    @DisplayName("a reroll also excludes whatever has already sat at that position this period")
    void rerollExcludesTheAlreadySeen() {
        List<Candidate> shown = List.of(new Candidate("easy_1", "Easy", 1.0));
        Set<String> excluded = SlotRerollEngine.excludeAll(shown, ID, Set.of("easy_4"));
        assertTrue(excluded.contains("easy_1"));
        assertTrue(excluded.contains("easy_4"));
    }

    @Test
    @DisplayName("a replacement is null when nothing distinct qualifies, so nothing is charged")
    void noAlternativeAnswersNull() {
        List<Candidate> onlyOne = List.of(new Candidate("easy_1", "Easy", 1.0));
        assertNull(WeightedSlotDraw.drawReplacement(onlyOne, PoolSlot.tier("Easy", 1), ID, WEIGHT,
                MATCHER, Set.of("easy_1"), 1L));
    }

    // ==================== Slot eligibility ====================

    @Test
    @DisplayName("a slot with no grade takes anybody; a graded one matches case-insensitively")
    void slotEligibility() {
        assertTrue(PoolSlot.ANY.accepts("Hard", null));
        assertTrue(PoolSlot.ANY.accepts(null, null));
        assertTrue(PoolSlot.tier("Hard", 1).accepts("hard", null));
        assertFalse(PoolSlot.tier("Hard", 1).accepts("Easy", null));
        assertFalse(PoolSlot.tier("Hard", 1).accepts(null, null));
    }

    @Test
    @DisplayName("a reroll spec caps what it says it caps, and a missing cap is uncapped")
    void rerollCaps() {
        RerollSpec capped = RerollSpec.of(null, 3);
        assertTrue(capped.allows(2));
        assertFalse(capped.allows(3));
        assertEquals(1, capped.remaining(2));

        RerollSpec uncapped = RerollSpec.of(null, 0);
        assertTrue(uncapped.allows(9999));
        assertEquals(-1, uncapped.remaining(9999));
        assertFalse(uncapped.isPaid());
    }
}
