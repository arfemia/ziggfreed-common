package com.ziggfreed.common.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PowerAggregation#fold} across every {@link AggregationMode} over
 * participant arrays of size 1..10, plus the empty / DISABLED identity and
 * {@link AggregationMode#fromName} parsing.
 */
class PowerAggregationTest {

    private static final double EPS = 1e-9;

    @Test
    void soloReturnsFirstParticipant() {
        assertEquals(42.0, PowerAggregation.fold(new double[] { 42.0 }, AggregationMode.SOLO), EPS);
        assertEquals(42.0, PowerAggregation.fold(new double[] { 42.0, 99.0, 7.0 }, AggregationMode.SOLO), EPS);
    }

    @Test
    void averageIsArithmeticMean() {
        assertEquals(20.0, PowerAggregation.fold(new double[] { 10.0, 20.0, 30.0 }, AggregationMode.AVERAGE), EPS);
        // size 1..10: mean of 1..10 = 5.5
        double[] oneToTen = new double[10];
        for (int i = 0; i < 10; i++) {
            oneToTen[i] = i + 1;
        }
        assertEquals(5.5, PowerAggregation.fold(oneToTen, AggregationMode.AVERAGE), EPS);
    }

    @Test
    void peakIsMaximum() {
        assertEquals(50.0, PowerAggregation.fold(new double[] { 10.0, 50.0, 30.0 }, AggregationMode.PEAK), EPS);
        assertEquals(90.0, PowerAggregation.fold(new double[] { 90.0, 10.0 }, AggregationMode.PEAK), EPS);
    }

    @Test
    void weightedEqualPowersReduceToMean() {
        assertEquals(10.0, PowerAggregation.fold(new double[] { 10.0, 10.0, 10.0 }, AggregationMode.WEIGHTED), EPS);
    }

    @Test
    void weightedBiasesTowardHighPowers() {
        // sum(p^2)/sum(p) = (100 + 8100) / (10 + 90) = 8200 / 100 = 82 (a plain mean would be 50)
        assertEquals(82.0, PowerAggregation.fold(new double[] { 10.0, 90.0 }, AggregationMode.WEIGHTED), EPS);
        // a zero participant contributes no weight: (0 + 10000)/(0 + 100) = 100
        assertEquals(100.0, PowerAggregation.fold(new double[] { 0.0, 100.0 }, AggregationMode.WEIGHTED), EPS);
    }

    @Test
    void weightedAllNonPositiveFallsBackToMean() {
        assertEquals(0.0, PowerAggregation.fold(new double[] { 0.0, 0.0, 0.0 }, AggregationMode.WEIGHTED), EPS);
    }

    @Test
    void emptyAndNullAndDisabledFoldToZero() {
        assertEquals(0.0, PowerAggregation.fold(new double[0], AggregationMode.AVERAGE), EPS);
        assertEquals(0.0, PowerAggregation.fold(null, AggregationMode.PEAK), EPS);
        assertEquals(0.0, PowerAggregation.fold(new double[] { 99.0 }, AggregationMode.DISABLED), EPS);
        assertEquals(0.0, PowerAggregation.fold(new double[] { 99.0 }, null), EPS);
    }

    @Test
    void fromNameParsesCaseInsensitivelyWithFallback() {
        assertEquals(AggregationMode.AVERAGE, AggregationMode.fromName("average", AggregationMode.DISABLED));
        assertEquals(AggregationMode.PEAK, AggregationMode.fromName("  PEAK  ", AggregationMode.SOLO));
        assertEquals(AggregationMode.WEIGHTED, AggregationMode.fromName(null, AggregationMode.WEIGHTED));
        assertEquals(AggregationMode.AVERAGE, AggregationMode.fromName("nonsense", AggregationMode.AVERAGE));
    }
}
