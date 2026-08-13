package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.Quest.Repeat.Reset;

/**
 * The calendar arithmetic behind a {@code Reset} window, exercised by handing it instants rather
 * than by waiting for one.
 *
 * <p>Every expected boundary is computed by hand from the epoch here rather than read back out of
 * the code under test: a test that asks the implementation what the answer is proves only that it is
 * consistent with itself, which is exactly the failure mode a calendar has.
 */
class RepeatPeriodTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final long MINUTE = 60_000L;

    /** Thursday 1 January 1970, 00:00 UTC, is epoch 0 - which is what the weekday shift is measured from. */
    private static final long EPOCH_THURSDAY = 0L;

    private static Reset daily(int atMinutes) {
        return new Reset(Reset.Period.DAILY, atMinutes, DayOfWeek.MONDAY, 1);
    }

    private static Reset weekly(DayOfWeek start) {
        return new Reset(Reset.Period.WEEKLY, 0, start, 1);
    }

    @Test
    void aDailyWindowRollsOverAtMidnight() {
        Reset reset = daily(0);
        long midnight = 10 * DAY;
        assertNotEquals(RepeatPeriod.periodIndex(reset, midnight - 1),
                RepeatPeriod.periodIndex(reset, midnight),
                "one millisecond either side of midnight is two different days");
        assertEquals(midnight, RepeatPeriod.periodStartMs(reset, midnight));
        assertEquals(midnight + DAY, RepeatPeriod.nextBoundaryMs(reset, midnight));
    }

    @Test
    void atMinutesMovesTheBoundaryAndDragsTheDayWithIt() {
        Reset reset = daily(240);
        long fourAm = 10 * DAY + 240 * MINUTE;
        assertEquals(fourAm, RepeatPeriod.periodStartMs(reset, fourAm));
        assertTrue(RepeatPeriod.samePeriod(reset, fourAm - MINUTE, fourAm - 2 * MINUTE),
                "03:58 and 03:59 are still the same, earlier day");
        assertNotEquals(RepeatPeriod.periodIndex(reset, fourAm - MINUTE),
                RepeatPeriod.periodIndex(reset, fourAm),
                "03:59 belongs to the PREVIOUS day when the window starts at 04:00");
    }

    @Test
    void aWeeklyWindowStartingOnMondayLandsOnAMonday() {
        Reset reset = weekly(DayOfWeek.MONDAY);
        // The epoch was a Thursday, so the Monday four days later is 4 January 1970.
        long mondayMidnight = EPOCH_THURSDAY + 4 * DAY;
        assertEquals(mondayMidnight, RepeatPeriod.periodStartMs(reset, mondayMidnight));
        assertEquals(mondayMidnight, RepeatPeriod.periodStartMs(reset, mondayMidnight + 3 * DAY),
                "any instant inside the week reports the same Monday");
        assertEquals(mondayMidnight + 7 * DAY, RepeatPeriod.nextBoundaryMs(reset, mondayMidnight));
    }

    @Test
    void changingTheStartDayMovesTheBoundaryByExactlyThatMuch() {
        long monday = EPOCH_THURSDAY + 4 * DAY;
        long sunday = monday - DAY;
        assertEquals(monday, RepeatPeriod.periodStartMs(weekly(DayOfWeek.MONDAY), monday + DAY));
        assertEquals(sunday, RepeatPeriod.periodStartMs(weekly(DayOfWeek.SUNDAY), monday + DAY),
                "a week starting on Sunday starts exactly one day earlier");
    }

    @Test
    void theNextBoundaryIsAlwaysStrictlyAhead() {
        Reset reset = daily(0);
        for (long instant : new long[] {0L, 1L, 10 * DAY, 10 * DAY + 1, 999L * DAY}) {
            assertTrue(RepeatPeriod.nextBoundaryMs(reset, instant) > instant,
                    "a boundary that is not ahead reads to a player as back at a moment already gone");
        }
    }

    @Test
    void anInstantBeforeTheEpochStillIndexesMonotonically() {
        Reset reset = daily(0);
        assertTrue(RepeatPeriod.periodIndex(reset, -3 * DAY) < RepeatPeriod.periodIndex(reset, -DAY),
                "a clock set before 1970 must still count forwards, which plain division does not");
        assertEquals(-3 * DAY, RepeatPeriod.periodStartMs(reset, -3 * DAY + 1));
    }

    @Test
    void aClockNearTheEndOfTimeSaturatesRatherThanWrapping() {
        assertEquals(Long.MAX_VALUE, RepeatPeriod.nextBoundaryMs(daily(0), Long.MAX_VALUE - 10L),
                "a wrapped boundary would read as offerable right now, which is the wrong way to fail");
    }

    @Test
    void twoInstantsInOneWindowAreTheSameWindow() {
        Reset reset = daily(0);
        long start = 10 * DAY;
        assertTrue(RepeatPeriod.samePeriod(reset, start, start + DAY - 1));
        assertTrue(!RepeatPeriod.samePeriod(reset, start, start + DAY));
    }

    @Test
    void aWeeklyWindowIsSevenTimesADailyOne() {
        assertEquals(DAY, RepeatPeriod.lengthMs(daily(0)));
        assertEquals(7 * DAY, RepeatPeriod.lengthMs(weekly(DayOfWeek.MONDAY)));
    }
}
