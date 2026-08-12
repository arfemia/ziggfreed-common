package com.ziggfreed.common.loot.stamp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.util.WeightedPick;

/**
 * DECIDES what a stamp writes. Pure: given a {@link StampSpec}, what the item already carries, a
 * factor lookup and a sample source, it answers a {@link StampPlan} without touching an item, a
 * store, or a world. {@link Stamper} is the half that then writes it.
 *
 * <p>The pass runs in four steps, and each one is where a specific class of authoring mistake would
 * otherwise turn into a runtime surprise:
 *
 * <ol>
 *   <li><b>Gather</b> the candidates: the referenced pool's entries first, then any written inline.
 *       An id no pool answers to is skipped, not fatal.</li>
 *   <li><b>Choose</b>: every {@code Always} entry lands, plus however many the lottery draws. The
 *       lottery runs through the shared weighted-pick primitive, so a stamp draws the same way loot
 *       does.</li>
 *   <li><b>Roll</b> each chosen entry to a whole number of points, adding its factor terms.</li>
 *   <li><b>Hold</b> the result inside the ceilings, in authored order, subtracting as it goes so the
 *       budget is spent once rather than once per entry.</li>
 * </ol>
 *
 * <p>If step 4 cuts everything away, the answer is {@link StampPlan#DENIED} rather than an empty
 * plan: the item is full, and the caller must abort before charging for the attempt.
 */
public final class StampCapEngine {

    private StampCapEngine() {
    }

    /**
     * Resolve {@code spec} against what {@code inspection} says the item already carries.
     *
     * @param lookup where factor readings come from (a snapshot, or a fixture map in a test)
     * @param sample a fresh {@code [0,1)} number per draw; inject a pinned one to test
     */
    @Nonnull
    public static StampPlan resolve(@Nullable StampSpec spec, @Nonnull StampInspection inspection,
            @Nonnull FactorLookup lookup, @Nonnull DoubleSupplier sample) {
        if (spec == null) {
            return StampPlan.NOTHING;
        }
        List<StatRollEntry> candidates = candidates(spec);
        if (candidates.isEmpty()) {
            return StampPlan.NOTHING;
        }

        List<StatRollEntry> chosen = choose(spec, candidates, sample);
        if (chosen.isEmpty()) {
            return StampPlan.NOTHING;
        }

        List<StatRoll> rolled = roll(chosen, lookup, sample);
        if (rolled.isEmpty()) {
            return StampPlan.NOTHING;
        }

        return clamp(rolled, spec.getCaps(), inspection, lookup);
    }

    // ==================== step 1: gather ====================

    /**
     * The candidate entries: the referenced {@link RollPoolAsset}'s entries first, then the spec's
     * own inline ones. A pool id nothing answers to contributes nothing rather than failing the
     * stamp - the validator reports it where it is cheap to fix.
     */
    @Nonnull
    public static List<StatRollEntry> candidates(@Nonnull StampSpec spec) {
        List<StatRollEntry> out = new ArrayList<>();
        String poolId = spec.getPool();
        if (poolId != null && !poolId.isBlank()) {
            RollPoolAsset pool = RollPoolConfig.getInstance().resolve(poolId);
            if (pool != null && pool.getEntries() != null) {
                out.addAll(Arrays.asList(pool.getEntries()));
            }
        }
        if (spec.getEntries() != null) {
            out.addAll(Arrays.asList(spec.getEntries()));
        }
        out.removeIf(entry -> entry == null || entry.isBlank());
        return out;
    }

    // ==================== step 2: choose ====================

    /**
     * Every {@code Always} entry plus the lottery's draws, in that order. An {@code Always} entry
     * never costs a pick and never competes for one, which is what makes "a guaranteed baseline plus
     * a lucky extra" a two-line authoring job.
     */
    @Nonnull
    private static List<StatRollEntry> choose(@Nonnull StampSpec spec,
            @Nonnull List<StatRollEntry> candidates, @Nonnull DoubleSupplier sample) {
        List<StatRollEntry> always = new ArrayList<>();
        List<StatRollEntry> lottery = new ArrayList<>();
        for (StatRollEntry entry : candidates) {
            if (entry.isAlways()) {
                always.add(entry);
            } else {
                lottery.add(entry);
            }
        }
        List<StatRollEntry> chosen = new ArrayList<>(always);
        int picks = pickCount(spec.getPicks(), sample);
        if (picks > 0 && !lottery.isEmpty()) {
            chosen.addAll(WeightedPick.some(lottery, StatRollEntry::effectiveWeight, picks,
                    spec.isUnique(), sample));
        }
        return spec.isUnique() ? dedupeByStat(chosen) : chosen;
    }

    /**
     * How many the lottery draws. No {@code Picks} authored means ZERO - a deliberate default, so a
     * spec that authors only {@code Always} entries is fully predictable and a spec that forgot its
     * Picks is visibly inert rather than quietly handing out one free stat.
     */
    private static int pickCount(@Nullable StampSpec.Picks picks, @Nonnull DoubleSupplier sample) {
        if (picks == null) {
            return 0;
        }
        int lo = picks.effectiveMin();
        int hi = picks.effectiveMax();
        if (hi <= lo) {
            return lo;
        }
        return lo + (int) Math.floor(sample.getAsDouble() * (hi - lo + 1));
    }

    @Nonnull
    private static List<StatRollEntry> dedupeByStat(@Nonnull List<StatRollEntry> entries) {
        List<StatRollEntry> out = new ArrayList<>(entries.size());
        Set<String> seen = new HashSet<>();
        for (StatRollEntry entry : entries) {
            if (seen.add(entry.getStat().toLowerCase(Locale.ROOT))) {
                out.add(entry);
            }
        }
        return out;
    }

    // ==================== step 3: roll ====================

    /**
     * Each chosen entry rolled to whole points: a uniform draw inside {@code [Min, Max]} plus the
     * entry's weighted factor terms, rounded. An entry that rounds to zero or less is dropped here -
     * writing a zero-point stat would leave a line on the item that means nothing.
     */
    @Nonnull
    private static List<StatRoll> roll(@Nonnull List<StatRollEntry> chosen, @Nonnull FactorLookup lookup,
            @Nonnull DoubleSupplier sample) {
        List<StatRoll> out = new ArrayList<>(chosen.size());
        for (StatRollEntry entry : chosen) {
            StatRollEntry.Points points = entry.getPoints();
            double min = points != null ? points.effectiveMin() : StatRollEntry.DEFAULT_POINTS;
            double max = points != null ? points.effectiveMax() : min;
            double value = max > min ? min + sample.getAsDouble() * (max - min) : min;
            if (points != null) {
                value += FactorFormula.sum(points.getFactors(), lookup.asFunction());
            }
            int whole = (int) Math.round(value);
            if (whole > 0) {
                out.add(new StatRoll(entry.getStat(), whole));
            }
        }
        return out;
    }

    // ==================== step 4: hold ====================

    /**
     * Hold {@code rolled} inside the ceilings, in authored order, spending the budget as it goes.
     * Everything cut away means {@link StampPlan#DENIED}.
     */
    @Nonnull
    private static StampPlan clamp(@Nonnull List<StatRoll> rolled, @Nullable StampSpec.Caps caps,
            @Nonnull StampInspection inspection, @Nonnull FactorLookup lookup) {
        Double budget = effectiveBudget(caps, lookup);
        Map<String, Double> perStat = caps == null ? Map.of() : caps.perStatOrEmpty();

        double spent = 0.0;
        List<StatRoll> held = new ArrayList<>(rolled.size());
        for (StatRoll roll : rolled) {
            double points = roll.points();
            Double statCap = perStat.get(roll.statId());
            if (statCap != null) {
                points = Math.min(points, Math.max(0.0, statCap - inspection.pointsOf(roll.statId())));
            }
            if (budget != null) {
                points = Math.min(points, Math.max(0.0, budget - inspection.totalPoints() - spent));
            }
            int whole = (int) Math.floor(points);
            if (whole > 0) {
                held.add(new StatRoll(roll.statId(), whole));
                spent += whole;
            }
        }
        return held.isEmpty() ? StampPlan.DENIED : StampPlan.of(held);
    }

    /**
     * The ceiling that actually binds: the LOWEST of every authored {@link StampSpec.Budget}, or
     * null when none is authored (no total ceiling at all - a per-stat one may still bind).
     *
     * <p>Lowest, not highest and not summed, is the whole point: it lets an author write a hard
     * absolute maximum beside an earned, factor-scaled allowance and have the attempt held by
     * whichever is tighter right now. An entry authoring both routes or neither says nothing
     * measurable and is skipped; the validator names it.
     */
    @Nullable
    public static Double effectiveBudget(@Nullable StampSpec.Caps caps, @Nonnull FactorLookup lookup) {
        if (caps == null || caps.getBudgets() == null || caps.getBudgets().length == 0) {
            return null;
        }
        Double lowest = null;
        for (StampSpec.Budget budget : caps.getBudgets()) {
            if (budget == null) {
                continue;
            }
            Double value;
            if (budget.isFlat()) {
                value = budget.getPoints();
            } else if (budget.isFactorScaled()) {
                value = budget.getPointsPer() * FactorFormula.sum(budget.getFactors(), lookup.asFunction());
            } else {
                continue;
            }
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            lowest = lowest == null ? value : Math.min(lowest, value);
        }
        return lowest;
    }
}
