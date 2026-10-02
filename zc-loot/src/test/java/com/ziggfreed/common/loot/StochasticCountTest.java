package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;

/**
 * The fractional-amount rule: the whole part always, the leftover fraction as the chance of exactly
 * one more, drawn at most once. Driven with counting, pinned sources, so each case says both what it
 * answered and how many times it drew. Amounts are this test's own.
 */
class StochasticCountTest {

    /** A pinned source that counts how often it was asked. */
    private static final class Pinned implements DoubleSupplier {
        private final double sample;
        int draws;

        Pinned(double sample) {
            this.sample = sample;
        }

        @Override
        public double getAsDouble() {
            draws++;
            return sample;
        }
    }

    @Test
    void aWholeAmountPaysItselfAndNeverDraws() {
        Pinned source = new Pinned(0.0);
        assertEquals(3, StochasticCount.resolve(3.0, source));
        assertEquals(1, StochasticCount.resolve(1.0, source));
        assertEquals(0, source.draws);
    }

    @Test
    void theFractionIsTheChanceOfOneMoreComparedStrictlyBelow() {
        assertEquals(2, StochasticCount.resolve(1.5, new Pinned(0.49)));
        assertEquals(1, StochasticCount.resolve(1.5, new Pinned(0.5)), "a sample AT the fraction misses");
        assertEquals(1, StochasticCount.resolve(1.5, new Pinned(0.99)));
        assertEquals(1, StochasticCount.resolve(0.25, new Pinned(0.2)));
        assertEquals(0, StochasticCount.resolve(0.25, new Pinned(0.25)));
    }

    @Test
    void aFractionDrawsExactlyOnce() {
        Pinned source = new Pinned(0.4);
        assertEquals(3, StochasticCount.resolve(2.5, source));
        assertEquals(1, source.draws);
    }

    @Test
    void nothingToResolvePaysNothingAndNeverDraws() {
        Pinned source = new Pinned(0.0);
        assertEquals(0, StochasticCount.resolve(0.0, source));
        assertEquals(0, StochasticCount.resolve(-2.5, source));
        assertEquals(0, StochasticCount.resolve(Double.NaN, source));
        assertEquals(0, StochasticCount.resolve(Double.POSITIVE_INFINITY, source));
        assertEquals(0, StochasticCount.resolve(Double.NEGATIVE_INFINITY, source));
        assertEquals(0, source.draws);
    }

    @Test
    void aCountPastTheIntRangeSaturatesRatherThanWrapping() {
        assertEquals(Integer.MAX_VALUE, StochasticCount.resolve(1e12, new Pinned(0.0)));
        assertEquals(Integer.MAX_VALUE, StochasticCount.resolve(Integer.MAX_VALUE + 0.5, new Pinned(0.0)),
                "the extra one is never added on top of the ceiling");
    }
}
