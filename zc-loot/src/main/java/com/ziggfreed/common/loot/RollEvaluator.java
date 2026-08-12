package com.ziggfreed.common.loot;

import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorFormula;

/**
 * DECIDES what a {@link Roll} pays out. Nothing here touches an inventory, a world, or a store:
 * given a roll, a factor lookup, and a chance sample, it answers whether the roll hit and which
 * grants groups apply. {@link LootEngine} is the half that then goes and does it.
 *
 * <p>Keeping the decision pure is what makes loot testable at all. Every branch below - a condition
 * that shuts the gate, a chance that clamps to its ceiling, a ladder tie resolving to the last
 * floor - is exercised against a fixture map and a pinned sample, with no server anywhere.
 *
 * <p>The evaluation order is the one {@link Roll} documents, and the two step-skipping rules are
 * load-bearing: a failing condition means the chance is never rolled (so a gated roll does not
 * silently consume a sample), and a failing chance means the ladder is never evaluated (so a rare
 * tier cannot leak out of a roll that did not fire).
 */
public final class RollEvaluator {

    private RollEvaluator() {
    }

    /** What one evaluation decided: whether it hit, what to grant, and which cues were earned. */
    public static final class Outcome {

        /** The roll did not fire; nothing is granted and no cue plays. */
        public static final Outcome NONE = new Outcome(false, null, null, null, null);

        private final boolean hit;
        @Nullable private final LootGrants topGrants;
        @Nullable private final LootGrants floorGrants;
        @Nullable private final String topCue;
        @Nullable private final String floorCue;

        private Outcome(boolean hit, @Nullable LootGrants topGrants, @Nullable LootGrants floorGrants,
                @Nullable String topCue, @Nullable String floorCue) {
            this.hit = hit;
            this.topGrants = topGrants;
            this.floorGrants = floorGrants;
            this.topCue = topCue;
            this.floorCue = floorCue;
        }

        /** True once the conditions and the chance both passed; a floor need not have been reached. */
        public boolean isHit() {
            return hit;
        }

        /** The roll's own grants group, applied on any hit. */
        @Nullable
        public LootGrants getTopGrants() {
            return topGrants;
        }

        /** The reached ladder floor's grants group, applied IN ADDITION to the top-level one. */
        @Nullable
        public LootGrants getFloorGrants() {
            return floorGrants;
        }

        /** The roll's own cue, judged against {@link #getTopGrants()}. */
        @Nullable
        public String getTopCue() {
            return topCue;
        }

        /** The reached floor's cue, judged against {@link #getFloorGrants()}. */
        @Nullable
        public String getFloorCue() {
            return floorCue;
        }
    }

    /**
     * Evaluate {@code roll} against {@code lookup} (usually a {@link FactorSnapshot}) and
     * {@code chanceSample} (a fresh {@code [0,1)} number per call - injected so a test can pin it).
     */
    @Nonnull
    public static Outcome evaluate(@Nonnull Roll roll, @Nonnull FactorLookup lookup,
            @Nonnull DoubleSupplier chanceSample) {
        if (!FactorGate.pass(roll.getConditions(), lookup)) {
            return Outcome.NONE;
        }
        if (!chancePasses(roll.getChance(), lookup, chanceSample)) {
            return Outcome.NONE;
        }
        Roll.Ladder.Floor floor = highestFloor(roll.getLadder(), lookup);
        return new Outcome(true, roll.getGrants(), floor == null ? null : floor.getGrants(),
                roll.getCue(), floor == null ? null : floor.getCue());
    }

    /**
     * The chance gate. An absent formula always passes. Otherwise the formula is evaluated as a
     * PERCENTAGE, held inside its own authored {@code Clamp} and then inside {@code 0..100}
     * regardless, and compared against {@code chanceSample * 100}.
     *
     * <p>The outer {@code 0..100} hold is not redundant with the authored clamp: it is what makes a
     * negative penalty term safe (it can only take the chance down to never, not into nonsense) and
     * what stops a large positive stack from consuming a sample it was always going to beat. A
     * chance that lands at or below 0 short-circuits WITHOUT drawing a sample, so a roll that can
     * never fire costs nothing and leaves the stream where it was.
     */
    public static boolean chancePasses(@Nullable FactorFormula chance, @Nonnull FactorLookup lookup,
            @Nonnull DoubleSupplier chanceSample) {
        if (chance == null) {
            return true;
        }
        double percent = effectiveChancePercent(chance, lookup);
        if (percent <= Roll.MIN_CHANCE_PERCENT) {
            return false;
        }
        if (percent >= Roll.MAX_CHANCE_PERCENT) {
            return true;
        }
        return chanceSample.getAsDouble() * Roll.MAX_CHANCE_PERCENT < percent;
    }

    /**
     * The chance {@code chance} actually carries right now, in percent and already held inside
     * {@code 0..100}. Public because a UI that wants to SHOW the odds must show the same number the
     * roll uses, not a second derivation of it.
     */
    public static double effectiveChancePercent(@Nullable FactorFormula chance, @Nonnull FactorLookup lookup) {
        if (chance == null) {
            return Roll.MAX_CHANCE_PERCENT;
        }
        double value = chance.evaluate(lookup.asFunction());
        if (!Double.isFinite(value)) {
            return Roll.MIN_CHANCE_PERCENT;
        }
        return Math.max(Roll.MIN_CHANCE_PERCENT, Math.min(Roll.MAX_CHANCE_PERCENT, value));
    }

    /**
     * The reached ladder floor, or null when there is no ladder, no floors, or nothing reached.
     *
     * <p>The three rules, applied here and nowhere else: the value is the weighted term sum (an
     * absent or empty list resolving to 0), a floor's threshold is its reader-defaulted
     * {@code effectiveMin()} so a 0 tier IS reachable, and floors sharing a threshold resolve to the
     * LAST authored one. The validator warns about that duplicate rather than leaving the author to
     * discover which one won.
     */
    @Nullable
    public static Roll.Ladder.Floor highestFloor(@Nullable Roll.Ladder ladder, @Nonnull FactorLookup lookup) {
        if (ladder == null) {
            return null;
        }
        Roll.Ladder.Floor[] floors = ladder.getFloors();
        if (floors == null || floors.length == 0) {
            return null;
        }
        double value = ladderValue(ladder, lookup);
        Roll.Ladder.Floor best = null;
        double bestMin = Double.NEGATIVE_INFINITY;
        for (Roll.Ladder.Floor floor : floors) {
            if (floor == null) {
                continue;
            }
            double min = floor.effectiveMin();
            if (value >= min && min >= bestMin) {
                bestMin = min;
                best = floor;
            }
        }
        return best;
    }

    /** The summed, deliberately unclamped ladder value; an absent or empty term list reads 0. */
    public static double ladderValue(@Nullable Roll.Ladder ladder, @Nonnull FactorLookup lookup) {
        return ladder == null ? 0.0 : FactorFormula.sum(ladder.getFactors(), lookup.asFunction());
    }
}
