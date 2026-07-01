package com.ziggfreed.common.scaling;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one difficulty-scaling input abstraction, generic over scalar participant powers so it
 * serves BOTH open-world proximity groups AND 1-10 player instances with zero consumer-domain
 * types (no player / party / skill types leak into this library). A consumer reduces each
 * participant to a {@code double} at the call site and hands over the array.
 *
 * <ul>
 *   <li>{@code baseDifficulty} - the authored floor (a zone floor open-world, an instance preset
 *       floor instanced).</li>
 *   <li>{@code participantPowers} - 1 (solo) .. N (group / instance) power scalars, already reduced
 *       by the consumer. Owned by this context: do NOT mutate the array after construction (it is
 *       held by reference, not defensively copied, to keep the spawn hot path allocation-lean).</li>
 *   <li>{@code aggregationMode} - how {@link PowerAggregation} folds the powers.</li>
 *   <li>{@code instanceHandle} - {@code null} open-world; the opaque instance preset / party handle
 *       when instanced (this library never inspects it; a consumer downcasts it).</li>
 * </ul>
 */
public record ScalingContext(
        double baseDifficulty,
        @Nonnull double[] participantPowers,
        @Nonnull AggregationMode aggregationMode,
        @Nullable Object instanceHandle) {

    private static final double[] EMPTY = new double[0];

    /** Null-guards the array + mode so a caller can pass {@code null} for "no participants". */
    public ScalingContext {
        if (participantPowers == null) {
            participantPowers = EMPTY;
        }
        if (aggregationMode == null) {
            aggregationMode = AggregationMode.DISABLED;
        }
    }

    /** The participant powers folded under {@link #aggregationMode()} (0.0 when empty / DISABLED). */
    public double aggregatedPower() {
        return PowerAggregation.fold(participantPowers, aggregationMode);
    }

    /**
     * Open-world context: one already-aggregated region power scalar, no instance handle.
     * The spawn hook reads a cached per-region scalar and builds the context this way.
     */
    @Nonnull
    public static ScalingContext openWorld(double baseDifficulty, double regionPower, @Nonnull AggregationMode mode) {
        return new ScalingContext(baseDifficulty, new double[] { regionPower }, mode, null);
    }

    /** Single-participant context (one power, {@link AggregationMode#SOLO}, no instance handle). */
    @Nonnull
    public static ScalingContext solo(double baseDifficulty, double power) {
        return new ScalingContext(baseDifficulty, new double[] { power }, AggregationMode.SOLO, null);
    }
}
