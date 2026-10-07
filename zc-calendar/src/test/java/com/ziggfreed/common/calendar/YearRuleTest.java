package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;

import org.junit.jupiter.api.Test;

/**
 * How one year's run is dated: Easter Sunday by the Gregorian computus, the Nth weekday of a month (the
 * last, and a fifth the month lacks), a span around either, and which rules may run at all. The dates
 * are calendar facts, never balance.
 */
class YearRuleTest {

    @Test
    void easterSundayFollowsTheGregorianComputus() {
        assertEquals(LocalDate.of(2027, 3, 28), YearRule.Easter.sunday(2027));
        assertEquals(LocalDate.of(2028, 4, 16), YearRule.Easter.sunday(2028));
        assertEquals(LocalDate.of(2029, 4, 1), YearRule.Easter.sunday(2029));
        assertEquals(LocalDate.of(2030, 4, 21), YearRule.Easter.sunday(2030));
    }

    @Test
    void theFourthThursdayOfNovember2026IsThe26th() {
        assertEquals(LocalDate.of(2026, 11, 26),
                new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 4, 0, 0).anchor(2026));
    }

    @Test
    void theLastWeekdayAndAFifthTheMonthLacks() {
        assertEquals(LocalDate.of(2027, 5, 31),
                new YearRule.Weekday(Month.MAY, DayOfWeek.MONDAY, YearRule.Weekday.LAST, 0, 0).anchor(2027));
        assertEquals(LocalDate.of(2026, 10, 29),
                new YearRule.Weekday(Month.OCTOBER, DayOfWeek.THURSDAY, 5, 0, 0).anchor(2026),
                "October 2026 has five Thursdays");
        assertEquals(LocalDate.of(2026, 11, 26),
                new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 5, 0, 0).anchor(2026),
                "November 2026 has four, so the fifth falls back to the fourth");
    }

    @Test
    void aSpanAroundTheAnchorKeepsBothEnds() {
        RunDays days = new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 4, 6, 5).days(2026);
        assertEquals(new RunDays(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 1)), days);
        assertEquals(12L, days.length(), "six before, the day itself, five after");
        assertEquals(new RunDays(LocalDate.of(2027, 3, 18), LocalDate.of(2027, 4, 4)),
                new YearRule.Easter(10, 7).days(2027));
    }

    @Test
    void fixedDaysCrossTheNewYearIntoTheNextOne() {
        YearRule.Fixed winter = new YearRule.Fixed(MonthDay.of(12, 15), MonthDay.of(1, 6));
        assertTrue(winter.crossesNewYear());
        assertEquals(new RunDays(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 6)), winter.days(2026));
        assertFalse(winter.moves());
    }

    @Test
    void aRuleIsValidOnlyWhenEveryRunStartsInItsOwnYearAndLastsAtMost366Days() {
        assertTrue(new YearRule.Easter(80, 0).valid(), "80 days before March 22nd is January 1st");
        assertFalse(new YearRule.Easter(81, 0).valid(), "81 days before the earliest Easter is in the year before");
        assertTrue(new YearRule.Easter(0, 365).valid(), "366 days, both ends in");
        assertFalse(new YearRule.Easter(0, 366).valid(), "367 days");
        assertFalse(new YearRule.Easter(-1, 0).valid());
        assertFalse(new YearRule.Weekday(Month.JANUARY, DayOfWeek.MONDAY, 1, 7, 0).valid(),
                "the first Monday of January can be the 1st");
        assertTrue(new YearRule.Weekday(Month.JANUARY, DayOfWeek.MONDAY, 2, 7, 0).valid(),
                "the second is the 8th at the earliest");
        assertFalse(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 0, 0, 0).valid());
        assertFalse(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 6, 0, 0).valid());
        assertTrue(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, YearRule.Weekday.LAST, 0, 0).valid());
    }
}
