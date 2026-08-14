package com.ziggfreed.common.rotation;

import javax.annotation.Nonnull;

/**
 * The ONE seed a rotating pool's draw is keyed on: {@code (poolId, period)} folded into a single
 * number, so the active set is reproducible across servers and restarts.
 *
 * <p>That reproducibility is what lets a rotation carry no persisted state at all. Every player
 * asking what is on a board today computes the same seed, so they see the same board, and a server
 * that restarts mid-period recomputes exactly what was there before.
 *
 * <p><b>The per-position form is deliberately DISTINCT from the base form.</b> A single-position
 * reroll folds the position and its reroll count in after the base mix, so a replacement draw can
 * never collide with the draw it is replacing and a position re-rolled twice yields a different
 * candidate than it did the first time.
 */
public final class PoolSeed {

    /** An odd 64-bit constant, mixed in per fold step in the FNV style. */
    private static final long PRIME = 1099511628211L;

    /** The fold's starting value, likewise odd and large. */
    private static final long OFFSET_BASIS = 1125899906842597L;

    private PoolSeed() {
    }

    /**
     * The base seed for a whole pool's draw in one period. {@code rerollOffset} exists for a caller
     * that reshuffles an entire set at once; a per-position reroll uses the four-argument form
     * instead.
     */
    public static long mix(@Nonnull String poolId, long period, int rerollOffset) {
        long hash = OFFSET_BASIS;
        for (int i = 0; i < poolId.length(); i++) {
            hash = 31 * hash + poolId.charAt(i);
        }
        hash = hash * PRIME + period;
        hash = hash * PRIME + rerollOffset;
        return hash;
    }

    /**
     * The seed for rerolling ONE position: the pool and period folded first, then the position and
     * how many times it has been re-rolled. Distinct from {@link #mix(String, long, int)} on
     * purpose, so a replacement never lands on the base draw's own sequence.
     */
    public static long mix(@Nonnull String poolId, long period, int position, int rerollCount) {
        long hash = mix(poolId, period, 0);
        hash = hash * PRIME + position;
        hash = hash * PRIME + rerollCount;
        return hash;
    }
}
