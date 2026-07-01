package com.ziggfreed.common.scaling;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Folds a {@code double[]} of participant power scalars into ONE effective power under an
 * {@link AggregationMode}. Pure logic, zero engine coupling, freely unit-testable - the
 * generic core shared by open-world proximity groups and 1-10 player instances (a consumer
 * reduces each participant to a {@code double} before calling in).
 *
 * <p>An empty / {@code null} array and {@link AggregationMode#DISABLED} both fold to {@code 0.0}
 * (the identity delta: no participant power to add over the base difficulty).
 */
public final class PowerAggregation {

    private PowerAggregation() {
    }

    /**
     * @param powers participant power scalars (may be {@code null} or empty)
     * @param mode   how to combine them (a {@code null} mode is treated as {@link AggregationMode#DISABLED})
     * @return the folded power, or {@code 0.0} for an empty input / {@code DISABLED} mode
     */
    public static double fold(@Nullable double[] powers, @Nullable AggregationMode mode) {
        if (powers == null || powers.length == 0 || mode == null || mode == AggregationMode.DISABLED) {
            return 0.0;
        }
        switch (mode) {
            case SOLO:
                return powers[0];
            case PEAK:
                return peak(powers);
            case WEIGHTED:
                return weighted(powers);
            case AVERAGE:
            default:
                return mean(powers);
        }
    }

    private static double mean(@Nonnull double[] powers) {
        double sum = 0.0;
        for (double p : powers) {
            sum += p;
        }
        return sum / powers.length;
    }

    private static double peak(@Nonnull double[] powers) {
        double max = powers[0];
        for (int i = 1; i < powers.length; i++) {
            if (powers[i] > max) {
                max = powers[i];
            }
        }
        return max;
    }

    /**
     * Power-weighted mean: each participant's weight is its own (non-negative) power, so
     * {@code sum(p^2) / sum(p)}. Equal powers reduce to the plain mean; a mix biases toward the
     * higher powers ("high levels drive"). Non-positive powers contribute no weight; if every
     * power is non-positive the weight total is zero and we fall back to the plain mean.
     */
    private static double weighted(@Nonnull double[] powers) {
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (double p : powers) {
            double w = p > 0.0 ? p : 0.0;
            weightedSum += w * p;
            weightTotal += w;
        }
        return weightTotal > 0.0 ? weightedSum / weightTotal : mean(powers);
    }
}
