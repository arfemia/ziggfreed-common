package com.ziggfreed.common.scaling;

import javax.annotation.Nonnull;

/**
 * Resolves a {@link ScalingContext} into an effective difficulty: the authored floor plus a
 * band-clamped participant-power delta, clamped to the caller's difficulty caps. Pure logic,
 * zero engine coupling - the generic engine both open-world scaling and future instance scaling
 * call verbatim (only the {@link ScalingContext} inputs differ).
 *
 * <pre>
 *   effDiff = clamp( base + clamp(aggregatedPower - base, -bandWidth, +bandWidth), minCap, maxCap )
 * </pre>
 *
 * <p>A {@link AggregationMode#DISABLED} mode or an empty participant set returns the base difficulty
 * untouched (no clamp applied - the caller's authored floor stands as-is).
 */
public final class ScalingEngine {

    private ScalingEngine() {
    }

    /**
     * @param ctx       the scaling inputs (base difficulty + participant powers + mode)
     * @param bandWidth the maximum absolute difficulty swing the participant delta may add over the
     *                  floor (non-negative; a mod-side validator enforces this)
     * @param minCap    lower clamp on the result
     * @param maxCap    upper clamp on the result
     * @return the resolved effective difficulty
     */
    public static double resolve(@Nonnull ScalingContext ctx, double bandWidth, double minCap, double maxCap) {
        if (ctx.aggregationMode() == AggregationMode.DISABLED || ctx.participantPowers().length == 0) {
            return ctx.baseDifficulty();
        }
        double delta = clamp(ctx.aggregatedPower() - ctx.baseDifficulty(), -bandWidth, bandWidth);
        return clamp(ctx.baseDifficulty() + delta, minCap, maxCap);
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.min(Math.max(value, lo), hi);
    }
}
