package com.ziggfreed.common.rotation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.cost.Cost;

/**
 * What it takes to re-roll one position of a rotating pool, and how often a player may.
 *
 * <p>The RUNTIME terms, not the authored shape; the authoring layer folds its own group into one of
 * these.
 *
 * <p>The price is the ordinary {@link Cost} every other price in the library is written in, so a
 * reroll charged in two currencies, or in items, needs nothing new. The cap is how many a player
 * may spend before the pool turns over; zero is uncapped.
 *
 * <p>A spec with no price is a FREE reroll rather than no reroll at all, which is a real thing to
 * author. A pool offering no reroll carries no spec.
 */
public final class RerollSpec {

    private final Cost cost;
    private final int maxPerPeriod;

    private RerollSpec(@Nullable Cost cost, int maxPerPeriod) {
        this.cost = cost == null ? Cost.FREE : cost;
        this.maxPerPeriod = Math.max(0, maxPerPeriod);
    }

    /** Reroll terms; a null price is a free reroll, a non-positive cap is uncapped. */
    @Nonnull
    public static RerollSpec of(@Nullable Cost cost, int maxPerPeriod) {
        return new RerollSpec(cost, maxPerPeriod);
    }

    /** What one reroll costs. */
    @Nonnull
    public Cost cost() {
        return cost;
    }

    /** How many rerolls one period allows; zero is uncapped. */
    public int maxPerPeriod() {
        return maxPerPeriod;
    }

    /** True when rerolling costs something. */
    public boolean isPaid() {
        return !cost.isFree();
    }

    /** How many rerolls are left after {@code spent}; -1 when uncapped. */
    public int remaining(int spent) {
        return maxPerPeriod <= 0 ? -1 : Math.max(0, maxPerPeriod - spent);
    }

    /** True when a player who has spent {@code spent} rerolls this period may spend another. */
    public boolean allows(int spent) {
        return maxPerPeriod <= 0 || spent < maxPerPeriod;
    }

    @Override
    public String toString() {
        return "RerollSpec[" + cost + (maxPerPeriod > 0 ? ", max " + maxPerPeriod : "") + "]";
    }
}
