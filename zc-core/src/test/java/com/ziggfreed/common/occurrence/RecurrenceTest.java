package com.ziggfreed.common.occurrence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Month;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * How a monthly or weekly event comes round, as a value a reader that never sees the calendar can say in words:
 * the day or the Nth weekday, every N, the months (none for every month) and how long a run lasts. Each record
 * keeps only what its rule means, so two ways of writing one rule are one value.
 */
class RecurrenceTest {

    @Test
    void aMonthlyRuleIsOnADayOrOnTheNthWeekdayNeverBoth() {
        Recurrence.Monthly fair = Recurrence.Monthly.onWeekday(1, DayOfWeek.SUNDAY, 1, Set.of(),
                Recurrence.Length.days(7));
        assertEquals(DayOfWeek.SUNDAY, fair.weekday());
        assertEquals(1, fair.nth());
        assertEquals(0, fair.day(), "a weekday rule names no day of the month");

        Recurrence.Monthly market = Recurrence.Monthly.onDay(15, 1, Set.of(Month.MARCH), Recurrence.Length.days(2));
        assertNull(market.weekday());
        assertEquals(15, market.day());
        assertEquals(0, market.nth(), "a day rule names no Nth weekday");

        assertEquals(new Recurrence.Monthly(0, DayOfWeek.FRIDAY, Recurrence.LAST, 1, Set.of(),
                Recurrence.Length.days(1)), new Recurrence.Monthly(31, DayOfWeek.FRIDAY, Recurrence.LAST, 1, Set.of(),
                Recurrence.Length.days(1)), "a day written beside a weekday is unused, so it is not kept");
    }

    @Test
    void everyMonthIsNoMonthsAndListedMonthsComeInCalendarOrder() {
        Recurrence.Weekly allYear = new Recurrence.Weekly(DayOfWeek.SATURDAY, 1, EnumSet.allOf(Month.class),
                Recurrence.Length.days(1));
        assertTrue(allYear.months().isEmpty(), "every month reads as none named");
        assertTrue(new Recurrence.Weekly(DayOfWeek.SATURDAY, 1, null, Recurrence.Length.days(1)).months().isEmpty());

        Recurrence.Monthly quarterly = Recurrence.Monthly.onDay(15, 1,
                Set.of(Month.DECEMBER, Month.MARCH, Month.SEPTEMBER, Month.JUNE), Recurrence.Length.days(2));
        assertEquals(List.of(Month.MARCH, Month.JUNE, Month.SEPTEMBER, Month.DECEMBER),
                List.copyOf(quarterly.months()), "January first, however they were written");
        assertEquals(1, new Recurrence.Weekly(DayOfWeek.MONDAY, 0, Set.of(), Recurrence.Length.days(7)).every(),
                "a rule comes round at least every week or month");
    }

    @Test
    void aRunLastsItsDaysItsHoursOrUntilTheNextAndTheFirstOfThoseItNamesWins() {
        Recurrence.Length contest = Recurrence.Length.timed(LocalTime.of(14, 0), Duration.ofHours(2));
        assertEquals(LocalTime.of(14, 0), contest.at());
        assertEquals(Duration.ofHours(2), contest.duration());
        assertEquals(0, contest.days(), "a length wins over days");
        assertFalse(contest.untilNext());

        Recurrence.Length untilNext = new Recurrence.Length(7, LocalTime.of(18, 0), Duration.ofHours(2), true);
        assertTrue(untilNext.untilNext());
        assertNull(untilNext.duration(), "until the next wins over a length");
        assertEquals(0, untilNext.days(), "and over days");
        assertEquals(LocalTime.of(18, 0), untilNext.at(), "it still starts at its time of day");

        assertNull(Recurrence.Length.days(2, LocalTime.MIDNIGHT).at(), "midnight is no time of day: whole days");
        assertEquals(1, Recurrence.Length.days(0).days(), "a run lasts a day at least");
        assertEquals(Recurrence.Length.days(3), new Recurrence.Length(3, null, Duration.ZERO, false),
                "a length of nothing is no length: the days stand");
    }

    @Test
    void aSourceThatKnowsNoRulesHasNoRecurrence() {
        assertNull(OccurrenceSource.NONE.recurrence("traveling_fair", 0L));
    }
}
