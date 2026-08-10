package com.ziggfreed.common.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScalingEngine#resolve}: the floor +/- band-clamped power delta, the
 * min/max caps, and the DISABLED / empty short-circuit that returns the base difficulty untouched.
 */
class ScalingEngineTest {

    private static final double EPS = 1e-9;

    @Test
    void withinBandAddsFullDelta() {
        // base 20, agg 22 (AVERAGE of [22]), band 6 -> delta +2 -> 22
        ScalingContext ctx = ScalingContext.openWorld(20.0, 22.0, AggregationMode.AVERAGE);
        assertEquals(22.0, ScalingEngine.resolve(ctx, 6.0, 1.0, 100.0), EPS);
    }

    @Test
    void positiveDeltaClampedToBandWidth() {
        // base 20, agg 30, band 6 -> delta clamp(+10, -6, +6) = +6 -> 26
        ScalingContext ctx = ScalingContext.openWorld(20.0, 30.0, AggregationMode.AVERAGE);
        assertEquals(26.0, ScalingEngine.resolve(ctx, 6.0, 1.0, 100.0), EPS);
    }

    @Test
    void negativeDeltaClampedToBandWidth() {
        // base 20, agg 5, band 6 -> delta clamp(-15, -6, +6) = -6 -> 14
        ScalingContext ctx = ScalingContext.openWorld(20.0, 5.0, AggregationMode.AVERAGE);
        assertEquals(14.0, ScalingEngine.resolve(ctx, 6.0, 1.0, 100.0), EPS);
    }

    @Test
    void resultClampedToMaxCap() {
        // base 98, agg 130, band 6 -> base+6 = 104 -> clamp to maxCap 100
        ScalingContext ctx = ScalingContext.openWorld(98.0, 130.0, AggregationMode.AVERAGE);
        assertEquals(100.0, ScalingEngine.resolve(ctx, 6.0, 1.0, 100.0), EPS);
    }

    @Test
    void resultClampedToMinCap() {
        // base 4, agg 0, band 6 -> base-4 = 0 -> clamp to minCap 1
        ScalingContext ctx = ScalingContext.openWorld(4.0, 0.0, AggregationMode.AVERAGE);
        assertEquals(1.0, ScalingEngine.resolve(ctx, 6.0, 1.0, 100.0), EPS);
    }

    @Test
    void aggEqualToBaseReturnsBase() {
        ScalingContext ctx = ScalingContext.openWorld(30.0, 30.0, AggregationMode.AVERAGE);
        assertEquals(30.0, ScalingEngine.resolve(ctx, 6.0, 1.0, 100.0), EPS);
    }

    @Test
    void disabledModeReturnsBaseUntouched() {
        // DISABLED short-circuits before any clamp - base returned even outside the caps
        ScalingContext ctx = ScalingContext.openWorld(150.0, 999.0, AggregationMode.DISABLED);
        assertEquals(150.0, ScalingEngine.resolve(ctx, 6.0, 1.0, 100.0), EPS);
    }

    @Test
    void emptyParticipantsReturnsBaseUntouched() {
        ScalingContext ctx = new ScalingContext(42.0, new double[0], AggregationMode.AVERAGE, null);
        assertEquals(42.0, ScalingEngine.resolve(ctx, 6.0, 1.0, 100.0), EPS);
    }

    @Test
    void soloContextUsesFirstPowerAsDelta() {
        // base 10, solo power 18, band 20 -> delta +8 -> 18
        ScalingContext ctx = ScalingContext.solo(10.0, 18.0);
        assertEquals(18.0, ScalingEngine.resolve(ctx, 20.0, 1.0, 100.0), EPS);
    }
}
