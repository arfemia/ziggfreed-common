package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * How each year's runs are dated: Easter Sunday by the Gregorian computus, the Nth weekday of a month (the
 * last, and a fifth the month lacks), a span around either, several spans, a run each month or each week with
 * a time of day, a length, every Nth and some months, and which rules may run at all. The dates are calendar
 * facts, never balance.
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

    private static final YearRule.Cadence ALWAYS = YearRule.Cadence.ALWAYS;

    @Test
    void aRuleIsValidOnlyWhenEveryRunStartsInItsOwnYearAndNeverMeetsTheNextOne() {
        assertTrue(new YearRule.Easter(80, 0).valid(), "80 days before March 22nd is January 1st");
        assertFalse(new YearRule.Easter(81, 0).valid(), "81 days before the earliest Easter is in the year before");
        assertTrue(new YearRule.Easter(0, 349).valid(), "350 days, both ends in: two Easters are at least 350 days apart");
        assertFalse(new YearRule.Easter(0, 350).valid(), "351 days could meet the next year's run");
        assertFalse(new YearRule.Easter(-1, 0).valid());
        assertFalse(new YearRule.Weekday(Month.JANUARY, DayOfWeek.MONDAY, 1, 7, 0).valid(),
                "the first Monday of January can be the 1st");
        assertTrue(new YearRule.Weekday(Month.JANUARY, DayOfWeek.MONDAY, 2, 7, 0).valid(),
                "the second is the 8th at the earliest");
        assertTrue(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 4, 0, 363).valid(), "364 days: 52 weeks");
        assertFalse(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 4, 0, 364).valid(),
                "365 days could meet the next year's run");
        assertFalse(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 0, 0, 0).valid());
        assertFalse(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 6, 0, 0).valid());
        assertTrue(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, YearRule.Weekday.LAST, 0, 0).valid());
        assertFalse(new YearRule.Fixed(MonthDay.of(2, 29), MonthDay.of(2, 28)).valid(),
                "in a year without a 29th the next run starts on the 28th, the day this one ends");
        assertTrue(new YearRule.Fixed(MonthDay.of(3, 1), MonthDay.of(2, 29)).valid());
        assertFalse(new YearRule.FixedRuns(List.of(new YearRule.Fixed(MonthDay.of(2, 29), MonthDay.of(2, 28)))).valid());
        assertFalse(new YearRule.Monthly(1, null, 0, ALWAYS, YearRule.RunLength.ofDays(29)).valid(),
                "29 days from February 1st meet March's run");
        assertFalse(new YearRule.Monthly(0, null, 0, ALWAYS, YearRule.RunLength.ofDays(1)).valid(), "no day 0");
        assertFalse(new YearRule.Monthly(32, null, 0, ALWAYS, YearRule.RunLength.ofDays(1)).valid());
        assertFalse(new YearRule.Monthly(0, DayOfWeek.SUNDAY, 6, ALWAYS, YearRule.RunLength.ofDays(1)).valid());
        assertFalse(new YearRule.Monthly(1, null, 0, new YearRule.Cadence(1, null, Set.of()),
                YearRule.RunLength.ofDays(1)).valid(), "a rule needs a month to run in");
        assertFalse(new YearRule.Weekly(DayOfWeek.SATURDAY, ALWAYS, YearRule.RunLength.ofDays(8)).valid());
        assertFalse(new YearRule.Weekly(DayOfWeek.SATURDAY, ALWAYS, YearRule.RunLength.ofDays(0)).valid());
        assertFalse(new YearRule.Weekly(DayOfWeek.SUNDAY, ALWAYS,
                new YearRule.RunLength(1, LocalTime.of(14, 0), Duration.ofHours(169), false)).valid(),
                "a week and an hour meets the next Sunday's run");
        assertFalse(new YearRule.Weekly(DayOfWeek.SUNDAY, ALWAYS,
                new YearRule.RunLength(1, null, Duration.ZERO, false)).valid(), "a run lasts a moment at least");
        assertTrue(new YearRule.Weekly(DayOfWeek.MONDAY, new YearRule.Cadence(2, LocalDate.of(2026, 1, 5),
                YearRule.EVERY_MONTH), YearRule.RunLength.ofDays(14)).valid(), "every other week may last two");
        assertFalse(new YearRule.Weekly(DayOfWeek.MONDAY, new YearRule.Cadence(2, null, YearRule.EVERY_MONTH),
                YearRule.RunLength.ofDays(1)).valid(), "skipping weeks needs an anchor to count from");
        assertFalse(new YearRule.Monthly(1, null, 0, new YearRule.Cadence(2, LocalDate.of(2026, 1, 1),
                Set.of(Month.FEBRUARY, Month.APRIL)), YearRule.RunLength.ofDays(1)).valid(),
                "every other month from January never lands on February or April");
        assertFalse(new YearRule.Monthly(1, null, 0, new YearRule.Cadence(1, null, Set.of(Month.MARCH)),
                new YearRule.RunLength(1, null, null, true)).valid(), "until the next run needs every month");
    }

    @Test
    void theTravelingFairRunsTheFirstSundayOfEveryMonthForAWeek() {
        YearRule.Monthly fair = new YearRule.Monthly(0, DayOfWeek.SUNDAY, 1, ALWAYS, YearRule.RunLength.ofDays(7));
        List<RunDays> runs = fair.runs(2027);
        assertEquals(12, runs.size());
        assertEquals(new RunDays(LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 9)), runs.get(0));
        assertEquals(new RunDays(LocalDate.of(2027, 10, 3), LocalDate.of(2027, 10, 9)), runs.get(9));
        assertTrue(fair.valid());
        assertTrue(fair.several());
        assertTrue(fair.moves(), "a weekday's dates move from year to year");
        assertEquals("Monthly SUNDAY #1 x7", fair.describe());
    }

    @Test
    void aMonthlyDayTheMonthLacksFallsBackToItsLastAndOnlyTheMonthsNamedRun() {
        YearRule.Monthly last = new YearRule.Monthly(31, null, 0, ALWAYS, YearRule.RunLength.ofDays(1));
        assertEquals(LocalDate.of(2027, 2, 28), last.runs(2027).get(1).first());
        assertEquals(LocalDate.of(2028, 2, 29), last.runs(2028).get(1).first());
        assertFalse(last.moves(), "a day of the month is the same every year");
        YearRule.Monthly quarterly = new YearRule.Monthly(15, null, 0,
                new YearRule.Cadence(1, null, Set.of(Month.DECEMBER, Month.MARCH)), YearRule.RunLength.ofDays(2));
        assertEquals(List.of(new RunDays(LocalDate.of(2027, 3, 15), LocalDate.of(2027, 3, 16)),
                new RunDays(LocalDate.of(2027, 12, 15), LocalDate.of(2027, 12, 16))), quarterly.runs(2027),
                "in calendar order, whatever order the months were given in");
        assertEquals("Monthly day 15 x2 in 03,12", quarterly.describe());
    }

    @Test
    void aWeeklyRuleRunsEveryWeekAndItsLastRunMayCrossTheNewYear() {
        List<RunDays> saturdays = new YearRule.Weekly(DayOfWeek.SATURDAY, ALWAYS, YearRule.RunLength.ofDays(1)).runs(2026);
        assertEquals(52, saturdays.size());
        assertEquals(LocalDate.of(2026, 1, 3), saturdays.get(0).first());
        assertEquals(LocalDate.of(2026, 10, 10), saturdays.get(40).first(), "the forty-first Saturday");
        YearRule.Weekly weeks = new YearRule.Weekly(DayOfWeek.MONDAY, ALWAYS, YearRule.RunLength.ofDays(7));
        assertEquals(new RunDays(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)), weeks.runs(2026).get(51),
                "a run belongs to the year it starts in");
        assertEquals(LocalDate.of(2027, 1, 4), weeks.runs(2027).get(0).first());
        YearRule.Weekly summer = new YearRule.Weekly(DayOfWeek.SATURDAY,
                new YearRule.Cadence(1, null, Set.of(Month.JUNE, Month.JULY, Month.AUGUST)), YearRule.RunLength.ofDays(1));
        assertEquals(13, summer.runs(2026).size());
        assertEquals("Weekly SATURDAY x1 in 06,07,08", summer.describe());
    }

    @Test
    void theFishingContestStartsAtTwoInTheAfternoonAndLastsTwoHours() {
        YearRule.Weekly contest = new YearRule.Weekly(DayOfWeek.SUNDAY, ALWAYS,
                new YearRule.RunLength(1, LocalTime.of(14, 0), Duration.ofHours(2), false));
        List<RunDays> runs = contest.runs(2026);
        assertEquals(52, runs.size());
        assertEquals(new RunDays(LocalDateTime.of(2026, 1, 4, 14, 0), LocalDateTime.of(2026, 1, 4, 16, 0)), runs.get(0));
        assertEquals(LocalDate.of(2026, 1, 4), runs.get(0).last(), "a run inside one day is that day's");
        assertEquals("Weekly SUNDAY at 14:00 for PT2H", contest.describe());
        YearRule.Weekly derby = new YearRule.Weekly(DayOfWeek.SATURDAY, ALWAYS,
                new YearRule.RunLength(1, null, Duration.ofHours(24), false));
        assertEquals(new RunDays(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 3)), derby.runs(2026).get(0),
                "twenty-four hours from midnight is the whole day");
        YearRule.Weekly evenings = new YearRule.Weekly(DayOfWeek.FRIDAY, ALWAYS,
                new YearRule.RunLength(2, LocalTime.of(18, 0), null, false));
        assertEquals(new RunDays(LocalDateTime.of(2026, 1, 2, 18, 0), LocalDateTime.of(2026, 1, 4, 18, 0)),
                evenings.runs(2026).get(0), "with a time of day, Days counts whole days from it");
    }

    @Test
    void aRunMayLastUntilTheNextStarts() {
        YearRule.Monthly post = new YearRule.Monthly(1, null, 0, ALWAYS, new YearRule.RunLength(1, null, null, true));
        assertTrue(post.valid());
        List<RunDays> months = post.runs(2027);
        assertEquals(new RunDays(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28)), months.get(1), "the whole month");
        assertEquals(new RunDays(LocalDate.of(2027, 12, 1), LocalDate.of(2027, 12, 31)), months.get(11),
                "December's run lasts until January's starts");
        assertEquals("Monthly day 1 until next", post.describe());
    }

    @Test
    void everyNthWeekOrMonthCountsFromItsAnchorSoTwoEventsCanTakeTurns() {
        YearRule.Weekly first = new YearRule.Weekly(DayOfWeek.MONDAY,
                new YearRule.Cadence(7, LocalDate.of(2026, 1, 5), YearRule.EVERY_MONTH), YearRule.RunLength.ofDays(7));
        YearRule.Weekly second = new YearRule.Weekly(DayOfWeek.MONDAY,
                new YearRule.Cadence(7, LocalDate.of(2026, 1, 12), YearRule.EVERY_MONTH), YearRule.RunLength.ofDays(7));
        assertEquals(List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23), LocalDate.of(2026, 4, 13),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 26),
                LocalDate.of(2026, 12, 14)), first.runs(2026).stream().map(RunDays::first).toList());
        assertEquals(LocalDate.of(2026, 1, 12), second.runs(2026).get(0).first(), "the next rotation, a week later");
        assertEquals("Weekly MONDAY x7 every 7 from 2026-01-05", first.describe());
        YearRule.Monthly odd = new YearRule.Monthly(1, null, 0,
                new YearRule.Cadence(2, LocalDate.of(2026, 1, 1), YearRule.EVERY_MONTH), YearRule.RunLength.ofDays(28));
        assertEquals(List.of(Month.JANUARY, Month.MARCH, Month.MAY, Month.JULY, Month.SEPTEMBER, Month.NOVEMBER),
                odd.runs(2027).stream().map(run -> run.first().getMonth()).toList(), "every other month, year after year");
    }

    @Test
    void severalSpansAreSeveralRunsInTheOrderWritten() {
        YearRule.FixedRuns fairs = new YearRule.FixedRuns(List.of(
                new YearRule.Fixed(MonthDay.of(9, 20), MonthDay.of(9, 26)),
                new YearRule.Fixed(MonthDay.of(4, 10), MonthDay.of(4, 16))));
        assertEquals(List.of(new RunDays(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 26)),
                new RunDays(LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 16))), fairs.runs(2026),
                "as written: the window numbers them in this order, the first span run 1");
        assertTrue(fairs.several());
        assertFalse(fairs.moves());
        assertEquals("09-20..09-26, 04-10..04-16", fairs.describe());
    }

    // A run's number (the maintainer's ruling): a span's is its place in the list, a monthly run's its month and a
    // weekly run's its calendar week: weeks run Monday to Sunday, week 1 holds January 1st however short it is,
    // and a week is counted within its day's own year, never an ISO-8601 week-based year.
    @Test
    void aRunIsNumberedByItsPlaceItsMonthOrItsCalendarWeek() {
        YearRule.FixedRuns fairs = new YearRule.FixedRuns(List.of(
                new YearRule.Fixed(MonthDay.of(12, 1), MonthDay.of(12, 7)),
                new YearRule.Fixed(MonthDay.of(3, 1), MonthDay.of(3, 7))));
        assertEquals(1, fairs.number(fairs.runs(2026).get(0), 1), "a span is numbered by its place, December's too");
        assertEquals(2, fairs.number(fairs.runs(2026).get(1), 2));
        YearRule.Monthly quarterly = new YearRule.Monthly(15, null, 0,
                new YearRule.Cadence(1, null, Set.of(Month.MARCH, Month.DECEMBER)), YearRule.RunLength.ofDays(2));
        assertEquals(3, quarterly.number(quarterly.runs(2026).get(0), 1), "a monthly run by its month");
        assertEquals(12, quarterly.number(quarterly.runs(2026).get(1), 2));
        YearRule.Weekly thursdays = new YearRule.Weekly(DayOfWeek.THURSDAY,
                new YearRule.Cadence(1, null, Set.of(Month.DECEMBER)), YearRule.RunLength.ofDays(3));
        assertEquals(53, thursdays.number(thursdays.runs(2026).get(4), 5), "a weekly run by its week, from its start");
        Set<DayOfWeek> newYearsDays = EnumSet.noneOf(DayOfWeek.class);
        for (int year = 2023; year <= 2030; year++) {
            LocalDate newYear = LocalDate.of(year, 1, 1);
            newYearsDays.add(newYear.getDayOfWeek());
            assertEquals(1, YearRule.Weekly.weekOfYear(newYear), "January 1st " + year + " is in week 1");
        }
        assertEquals(EnumSet.allOf(DayOfWeek.class), newYearsDays, "whatever its weekday");
        assertEquals(1, YearRule.Weekly.weekOfYear(LocalDate.of(2026, 1, 4)), "2026's week 1 runs Thursday to Sunday");
        assertEquals(2, YearRule.Weekly.weekOfYear(LocalDate.of(2026, 1, 5)), "and week 2 starts on the Monday");
        assertEquals(1, YearRule.Weekly.weekOfYear(LocalDate.of(2023, 1, 1)), "a Sunday January 1st is week 1 alone");
        assertEquals(2, YearRule.Weekly.weekOfYear(LocalDate.of(2023, 1, 2)), "and Monday the 2nd starts week 2");
        assertEquals(53, YearRule.Weekly.weekOfYear(LocalDate.of(2026, 12, 31)));
        assertEquals(53, YearRule.Weekly.weekOfYear(LocalDate.of(2012, 12, 30)));
        assertEquals(54, YearRule.Weekly.weekOfYear(LocalDate.of(2012, 12, 31)),
                "a leap year starting on a Sunday ends on a Monday, in week 54");
        assertEquals(53, YearRule.Weekly.weekOfYear(LocalDate.of(2025, 12, 29)),
                "late December stays in its own year (ISO-8601 calls this day week 1 of 2026)");
        assertEquals(1, YearRule.Weekly.weekOfYear(LocalDate.of(2027, 1, 1)),
                "and early January in its own (ISO-8601 calls this day week 53 of 2026)");
    }

    // Where a number the year lacks would start, for seeking the run after it: its month, or its calendar week.
    @Test
    void aNumberTheYearLacksStartsWhereItsMonthOrWeekDoes() {
        YearRule.Monthly quarterly = new YearRule.Monthly(15, null, 0,
                new YearRule.Cadence(1, null, Set.of(Month.MARCH, Month.DECEMBER)), YearRule.RunLength.ofDays(2));
        assertEquals(LocalDateTime.of(2026, 5, 1, 0, 0), quarterly.numberStart(2026, 5));
        assertNull(quarterly.numberStart(2026, 13), "no thirteenth month");
        YearRule.Weekly mondays = new YearRule.Weekly(DayOfWeek.MONDAY, ALWAYS, YearRule.RunLength.ofDays(1));
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), mondays.numberStart(2026, 1), "week 1 starts on January 1st");
        assertEquals(LocalDateTime.of(2026, 1, 5, 0, 0), mondays.numberStart(2026, 2), "and week 2 on its Monday");
        assertEquals(LocalDateTime.of(2026, 12, 28, 0, 0), mondays.numberStart(2026, 53));
        assertNull(mondays.numberStart(2026, 54), "2026 has 53 weeks");
        assertNull(mondays.numberStart(2026, 0));
        assertEquals(LocalDateTime.of(2012, 12, 31, 0, 0), mondays.numberStart(2012, 54));
        assertNull(new YearRule.FixedRuns(List.of(new YearRule.Fixed(MonthDay.of(6, 1), MonthDay.of(6, 7))))
                .numberStart(2026, 2), "a place in a list names no date");
    }

    @Test
    void aSpanOfMonthDaysLastsAtMostAWholeLeapYear() {
        assertEquals(YearRule.MAX_RUN_DAYS, new YearRule.Fixed(MonthDay.of(1, 1), MonthDay.of(12, 31)).days(2028).length());
        assertEquals(YearRule.MAX_RUN_DAYS, new YearRule.Fixed(MonthDay.of(3, 1), MonthDay.of(2, 29)).days(2027).length(),
                "March 1st 2027 through February 29th 2028");
        long longest = 0;
        for (int from = 1; from <= 366; from++) {
            for (int to = 1; to <= 366; to++) {
                YearRule.Fixed span = new YearRule.Fixed(MonthDay.from(LocalDate.ofYearDay(2028, from)),
                        MonthDay.from(LocalDate.ofYearDay(2028, to)));
                for (int year = 2026; year <= 2028; year++) {
                    longest = Math.max(longest, span.days(year).length());
                }
            }
        }
        assertEquals(YearRule.MAX_RUN_DAYS, longest, "no span of month-days, crossing the new year or not, lasts longer");
    }

    @Test
    void noValidRuleEverMeetsItsOwnNextRun() {
        YearRule.Cadence everyOther = new YearRule.Cadence(2, LocalDate.of(2026, 1, 5), YearRule.EVERY_MONTH);
        for (YearRule rule : List.of(new YearRule.Easter(80, 269), new YearRule.Easter(0, 349),
                new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 5, 0, 363),
                new YearRule.Weekday(Month.JANUARY, DayOfWeek.MONDAY, YearRule.Weekday.LAST, 20, 343),
                new YearRule.Monthly(31, null, 0, ALWAYS, YearRule.RunLength.ofDays(28)),
                new YearRule.Monthly(0, DayOfWeek.SUNDAY, 5, ALWAYS, YearRule.RunLength.ofDays(28)),
                new YearRule.Monthly(0, DayOfWeek.FRIDAY, YearRule.Weekday.LAST, ALWAYS, YearRule.RunLength.ofDays(28)),
                new YearRule.Monthly(29, null, 0, new YearRule.Cadence(2, LocalDate.of(2026, 1, 1), YearRule.EVERY_MONTH),
                        YearRule.RunLength.ofDays(56)),
                new YearRule.Monthly(1, null, 0, ALWAYS, new YearRule.RunLength(1, null, null, true)),
                new YearRule.Weekly(DayOfWeek.FRIDAY, ALWAYS, YearRule.RunLength.ofDays(7)),
                new YearRule.Weekly(DayOfWeek.MONDAY, everyOther, YearRule.RunLength.ofDays(14)),
                new YearRule.Weekly(DayOfWeek.SUNDAY, ALWAYS,
                        new YearRule.RunLength(1, LocalTime.of(14, 0), Duration.ofHours(168), false)),
                new YearRule.Fixed(MonthDay.of(3, 1), MonthDay.of(2, 29)),
                new YearRule.Fixed(MonthDay.of(2, 29), MonthDay.of(2, 27)))) {
            assertTrue(rule.valid(), rule.describe() + " is valid");
            RunDays before = null;
            for (int year = 1970; year <= 2410; year++) {
                for (RunDays run : rule.runs(year)) {
                    assertEquals(year, run.first().getYear(), rule.describe() + ": a run starts in its own year");
                    if (before != null) {
                        assertFalse(run.meets(before), rule.describe() + ": " + run + " meets " + before);
                    }
                    before = run;
                }
            }
        }
    }

    // A repeating run lasts at most 365 days (not 366), so even one starting late on December 31st ends inside
    // the next year: the window looks one year back for a run going on, and no further.
    @Test
    void aRepeatingRunLastsAtMostAYearSoItNeverReachesTwoYearsOn() {
        YearRule.Cadence everyFourteen = new YearRule.Cadence(14, LocalDate.of(2026, 12, 1), YearRule.EVERY_MONTH);
        YearRule.Monthly longest = new YearRule.Monthly(31, null, 0, everyFourteen,
                new YearRule.RunLength(365, LocalTime.of(23, 0), null, false));
        assertTrue(longest.valid());
        RunDays run = longest.runs(2026).get(0);
        assertEquals(LocalDate.of(2026, 12, 31), run.first());
        assertEquals(2027, run.last().getYear(), "it ends inside the next year");
        assertFalse(new YearRule.Monthly(31, null, 0, everyFourteen,
                new YearRule.RunLength(366, LocalTime.of(23, 0), null, false)).valid(),
                "366 days from 23:00 on December 31st would run into the year after next");
        assertFalse(new YearRule.Weekly(DayOfWeek.FRIDAY, new YearRule.Cadence(60, LocalDate.of(2026, 1, 2),
                YearRule.EVERY_MONTH), new YearRule.RunLength(1, LocalTime.of(23, 0), Duration.ofDays(366), false))
                .valid());
    }
}
