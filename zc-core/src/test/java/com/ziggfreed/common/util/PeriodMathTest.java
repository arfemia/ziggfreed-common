package com.ziggfreed.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared recurring-window arithmetic every rotation and every repeat window indexes through. */
class PeriodMathTest {

    private static final long DAY = PeriodMath.DAY_MS;

    @Test
    @DisplayName("a day-long window indexes off the epoch grid and is stable within the day")
    void daysIndexOffTheEpoch() {
        assertEquals(0L, PeriodMath.periodIndex(DAY, 0L, 0L));
        assertEquals(0L, PeriodMath.periodIndex(DAY, 0L, DAY - 1));
        assertEquals(1L, PeriodMath.periodIndex(DAY, 0L, DAY));
        assertEquals(10L, PeriodMath.periodIndex(DAY, 0L, 10 * DAY + 12345L));
    }

    @Test
    @DisplayName("indexing stays monotonic for instants before the epoch")
    void negativeInstantsStayMonotonic() {
        assertEquals(-1L, PeriodMath.periodIndex(DAY, 0L, -1L));
        assertEquals(-1L, PeriodMath.periodIndex(DAY, 0L, -DAY));
        assertEquals(-2L, PeriodMath.periodIndex(DAY, 0L, -DAY - 1));
    }

    @Test
    @DisplayName("a positive anchor offset pulls every boundary earlier")
    void anchorOffsetShiftsBoundaries() {
        long hour = PeriodMath.HOUR_MS;
        assertEquals(0L, PeriodMath.periodStartMs(DAY, 0L, hour));
        // Shifting the grid by an hour moves the boundary an hour earlier in wall-clock terms.
        assertEquals(-hour, PeriodMath.periodStartMs(DAY, hour, hour * 2));
    }

    @Test
    @DisplayName("an instant sitting on a boundary has a whole window ahead of it")
    void onABoundaryTheWholeWindowIsLeft() {
        assertEquals(DAY, PeriodMath.millisUntilNext(DAY, 0L, 0L));
        assertEquals(DAY, PeriodMath.millisUntilNext(DAY, 0L, 5 * DAY));
        assertEquals(1L, PeriodMath.millisUntilNext(DAY, 0L, DAY - 1));
    }

    @Test
    @DisplayName("the next boundary is always strictly later than now")
    void nextBoundaryIsStrictlyLater() {
        assertEquals(DAY, PeriodMath.nextBoundaryMs(DAY, 0L, 0L));
        assertTrue(PeriodMath.nextBoundaryMs(DAY, 0L, 3 * DAY) > 3 * DAY);
        assertEquals(Long.MAX_VALUE, PeriodMath.nextBoundaryMs(DAY, 0L, Long.MAX_VALUE - 1));
    }

    @Test
    @DisplayName("two instants in one window are the same period, one either side are not")
    void samePeriodSplitsOnTheBoundary() {
        assertTrue(PeriodMath.samePeriod(DAY, 0L, 1L, DAY - 1));
        assertFalse(PeriodMath.samePeriod(DAY, 0L, DAY - 1, DAY));
    }

    @Test
    @DisplayName("the weekday anchor is zero for Thursday, the weekday the epoch fell on")
    void weekdayAnchorMeasuresFromThursday() {
        assertEquals(0L, PeriodMath.weekdayAnchorMs(DayOfWeek.THURSDAY));
        assertEquals(3 * DAY, PeriodMath.weekdayAnchorMs(DayOfWeek.MONDAY));
        assertEquals(0L, PeriodMath.weekdayAnchorMs(null));
    }

    @Test
    @DisplayName("a window with no length contains everything rather than dividing by zero")
    void zeroLengthDegrades() {
        assertEquals(0L, PeriodMath.periodIndex(0L, 0L, 12345L));
        assertEquals(12345L, PeriodMath.periodStartMs(0L, 0L, 12345L));
        assertEquals(0L, PeriodMath.millisUntilNext(0L, 0L, 12345L));
    }
}
