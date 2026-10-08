package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * A window of runs: both days in, crossing the new year, counted in a zone; several runs a year, a list of spans
 * numbered in the order written, a monthly run by its month and a weekly run by its calendar week; a run that
 * meets an earlier-written one set aside, and nothing dated before the floor.
 */
class AnnualWindowTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    @Test
    void bothDaysAreInsideTheWindow() {
        AnnualWindow window = AnnualWindow.parse("10-01", "11-03");
        assertNotNull(window);
        assertEquals(at("2026-10-01T00:00:00Z"), window.startMs(2026, UTC));
        assertEquals(at("2026-11-04T00:00:00Z"), window.endMs(2026, UTC),
                "the last day is in, so the run ends at the next midnight");
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2026-10-01T00:00:00Z"), UTC));
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2026-11-03T23:59:59Z"), UTC));
        assertNull(window.yearContaining(at("2026-11-04T00:00:00Z"), UTC));
        assertNull(window.yearContaining(at("2026-09-30T23:59:59Z"), UTC));
        assertFalse(window.crossesNewYear());
        assertEquals("10-01..11-03", window.toString());
    }

    @Test
    void aOneDayWindowRunsThatWholeDay() {
        AnnualWindow window = AnnualWindow.parse("10-31", "10-31");
        assertNotNull(window);
        assertEquals(at("2026-10-31T00:00:00Z"), window.startMs(2026, UTC));
        assertEquals(at("2026-11-01T00:00:00Z"), window.endMs(2026, UTC));
    }

    @Test
    void aWindowCrossingTheNewYearBelongsToTheYearItStarts() {
        AnnualWindow window = AnnualWindow.parse("12-20", "01-05");
        assertNotNull(window);
        assertTrue(window.crossesNewYear());
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2026-12-25T12:00:00Z"), UTC));
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2027-01-05T23:00:00Z"), UTC),
                "early January is still the run that began in December");
        assertNull(window.yearContaining(at("2027-01-06T00:00:00Z"), UTC));
        assertEquals(at("2027-01-06T00:00:00Z"), window.endMs(2026, UTC));
    }

    @Test
    void aZoneMovesEveryBoundaryToItsOwnMidnight() {
        AnnualWindow window = AnnualWindow.parse("10-29", "10-31");
        assertNotNull(window);
        ZoneId newYork = ZoneId.of("America/New_York");
        assertEquals(at("2026-10-29T04:00:00Z"), window.startMs(2026, newYork),
                "midnight in New York is 04:00 UTC while daylight time runs");
        assertEquals(at("2026-11-01T04:00:00Z"), window.endMs(2026, newYork));
    }

    @Test
    void february29thFallsBackToThe28thInAYearWithoutOne() {
        AnnualWindow window = AnnualWindow.parse("02-29", "02-29");
        assertNotNull(window);
        assertEquals(at("2027-02-28T00:00:00Z"), window.startMs(2027, UTC));
        assertEquals(at("2028-02-29T00:00:00Z"), window.startMs(2028, UTC));
    }

    @Test
    void onlyRealMonthDaysParse() {
        assertNull(AnnualWindow.parse("1-5", "11-03"), "two digits each");
        assertNull(AnnualWindow.parse("02-30", "03-01"));
        assertNull(AnnualWindow.parse("13-01", "12-31"));
        assertNull(AnnualWindow.parse(null, "12-31"));
        assertNotNull(AnnualWindow.parse(" 10-01 ", "11-03"), "surrounding spaces are forgiven");
    }

    @Test
    void aClockIsAZoneIdOrAnOffsetAndUtcByDefault() {
        assertEquals(ZoneOffset.UTC, AnnualWindow.zone(null));
        assertEquals(ZoneOffset.UTC, AnnualWindow.zone("  "));
        assertEquals(ZoneId.of("Europe/Paris"), AnnualWindow.zone("Europe/Paris"));
        assertEquals(ZoneOffset.ofHours(2), AnnualWindow.zone("+02:00"));
        assertNull(AnnualWindow.zone("Mars/Olympus_Mons"));
    }

    @Test
    void theNextStartIsStrictlyAfterNowAndNeverBeforeTheFirstYear() {
        AnnualWindow window = AnnualWindow.parse("10-01", "11-03");
        assertNotNull(window);
        assertEquals(at("2027-10-01T00:00:00Z"), window.nextStartMs(at("2026-10-01T00:00:00Z"), UTC, 2026),
                "a run starting this very instant is not the next one");
        assertEquals(at("2026-10-01T00:00:00Z"), window.nextStartMs(at("2026-06-01T00:00:00Z"), UTC, 2026));
        assertEquals(at("2030-10-01T00:00:00Z"), window.nextStartMs(at("2026-06-01T00:00:00Z"), UTC, 2030));
    }

    @Test
    void aWindowWithNoRunLeftAnswersNoNextStartAtOnce() {
        AnnualWindow window = AnnualWindow.of(null,
                Map.of(2026, new YearRule.Fixed(MonthDay.of(6, 1), MonthDay.of(6, 7))));
        assertNotNull(window);
        assertEquals(at("2026-06-01T00:00:00Z"), window.nextStartMs(at("2026-01-01T00:00:00Z"), UTC, 2026));
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertNull(window.nextStartMs(at("2026-07-01T00:00:00Z"), UTC, 2026)));
        assertNull(window.nextRunYear(2027));
        assertFalse(window.hasRun(2027));
        assertThrows(IllegalArgumentException.class, () -> window.startMs(2027, UTC),
                "a year with no run has no first instant to ask for");
    }

    @Test
    void theNextStartStopsAtTheLastFourDigitYear() {
        AnnualWindow window = AnnualWindow.parse("10-01", "11-03");
        assertNotNull(window);
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertNull(window.nextStartMs(at("9999-12-01T00:00:00Z"), UTC, 2026)));
    }

    private static YearRule.Fixed span(String first, String last) {
        return AnnualWindow.fixed(first, last);
    }

    private static RunDays days(String first, String last) {
        return new RunDays(LocalDate.parse(first), LocalDate.parse(last));
    }

    /** The numbers of {@code year}'s runs as the window keeps them, in order. */
    private static List<Integer> numbers(AnnualWindow window, int year) {
        return window.datedRuns(year).stream().map(AnnualWindow.DatedRun::number).toList();
    }

    // Run numbers are fixed by authored position (the maintainer's ruling): a span's number is its place in the
    // list, whatever its dates. Every question asked in time (going on, next, after, forced) is answered by the
    // dates, and the run it finds keeps its written number.
    @Test
    void aYearMayHoldSeveralRunsNumberedByTheOrderWrittenWhateverTheirDates() {
        AnnualWindow window = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("09-20", "09-26"), span("04-10", "04-16"))), Map.of());
        assertNotNull(window);
        assertEquals(List.of(days("2026-09-20", "2026-09-26"), days("2026-04-10", "2026-04-16")), window.runs(2026),
                "in the order written: the first span is run 1");
        assertEquals(days("2026-04-10", "2026-04-16"), window.run(2026, 2));
        assertNull(window.run(2026, 3));
        assertEquals(List.of(new AnnualWindow.DatedRun(2026, 1, days("2026-09-20", "2026-09-26")),
                new AnnualWindow.DatedRun(2026, 2, days("2026-04-10", "2026-04-16"))), window.datedRuns(2026));
        assertEquals(days("2026-09-20", "2026-09-26"), window.days(2026), "days(year) is the year's run 1");
        assertEquals(new AnnualWindow.DatedRun(2026, 1, days("2026-09-20", "2026-09-26")),
                window.runContaining(at("2026-09-21T12:00:00Z"), UTC));
        assertEquals(new AnnualWindow.DatedRun(2026, 2, days("2026-04-10", "2026-04-16")),
                window.runContaining(at("2026-04-12T12:00:00Z"), UTC));
        assertNull(window.runContaining(at("2026-06-01T12:00:00Z"), UTC), "between the runs");
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2026-09-21T12:00:00Z"), UTC));
        assertEquals(new AnnualWindow.DatedRun(2026, 1, days("2026-09-20", "2026-09-26")),
                window.nextRun(at("2026-06-01T12:00:00Z"), UTC, 2026), "the next by its dates, numbered as written");
        assertEquals(new AnnualWindow.DatedRun(2026, 2, days("2026-04-10", "2026-04-16")),
                window.nextRun(at("2026-01-01T00:00:00Z"), UTC, 2026));
        assertEquals(new AnnualWindow.DatedRun(2026, 1, days("2026-09-20", "2026-09-26")), window.after(2026, 2),
                "after the spring run comes the autumn one, by the dates");
        assertEquals(new AnnualWindow.DatedRun(2027, 2, days("2027-04-10", "2027-04-16")), window.after(2026, 1),
                "after the autumn run comes next spring's, still run 2");
        assertEquals(at("2026-04-10T00:00:00Z"), window.startMs(2026, 2, UTC));
        assertEquals(at("2026-04-17T00:00:00Z"), window.endMs(2026, 2, UTC));
        assertTrue(window.several());
        assertFalse(window.moves(), "the same days every year");
        assertEquals("09-20..09-26, 04-10..04-16", window.toString(), "the rule as written");
    }

    // A re-date never renumbers (the maintainer's ruling): a span moved past another keeps its place in the list,
    // and a monthly rule's runs keep their months whatever day of them the rule names.
    @Test
    void aReDateNeverRenumbersARunEvenOneMovedPastAnother() {
        AnnualWindow before = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("04-10", "04-16"), span("09-20", "09-26"))), Map.of());
        AnnualWindow after = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("10-01", "10-07"), span("09-20", "09-26"))), Map.of());
        assertEquals(1, before.runContaining(at("2026-04-12T12:00:00Z"), UTC).number());
        assertEquals(2, before.runContaining(at("2026-09-21T12:00:00Z"), UTC).number());
        assertEquals(new AnnualWindow.DatedRun(2026, 1, days("2026-10-01", "2026-10-07")),
                after.runContaining(at("2026-10-02T12:00:00Z"), UTC),
                "the first span moved past the second and is still run 1");
        assertEquals(2, after.runContaining(at("2026-09-21T12:00:00Z"), UTC).number(), "the second keeps its number");
        AnnualWindow sundays = AnnualWindow.of(new YearRule.Monthly(0, DayOfWeek.SUNDAY, 1, YearRule.Cadence.ALWAYS,
                YearRule.RunLength.ofDays(7)), Map.of());
        AnnualWindow saturdays = AnnualWindow.of(new YearRule.Monthly(0, DayOfWeek.SATURDAY, 2,
                YearRule.Cadence.ALWAYS, YearRule.RunLength.ofDays(3)), Map.of());
        for (int number = 1; number <= 12; number++) {
            assertEquals(Month.of(number), sundays.run(2026, number).first().getMonth());
            assertEquals(Month.of(number), saturdays.run(2026, number).first().getMonth(),
                    "run " + number + " is its month's run before and after the re-date");
        }
    }

    // A monthly run's number is its month and a weekly run's its week of the year (the maintainer's ruling), so
    // adding or dropping months or weeks never renumbers the others, and the numbers may skip.
    @Test
    void aMonthlyRunIsNumberedByItsMonthSoAddingMonthsNeverRenumbersTheRest() {
        AnnualWindow twice = AnnualWindow.of(new YearRule.Monthly(15, null, 0,
                new YearRule.Cadence(1, null, Set.of(Month.MARCH, Month.DECEMBER)), YearRule.RunLength.ofDays(2)),
                Map.of());
        AnnualWindow quarterly = AnnualWindow.of(new YearRule.Monthly(15, null, 0,
                new YearRule.Cadence(1, null, Set.of(Month.MARCH, Month.JUNE, Month.SEPTEMBER, Month.DECEMBER)),
                YearRule.RunLength.ofDays(2)), Map.of());
        assertEquals(List.of(new AnnualWindow.DatedRun(2026, 3, days("2026-03-15", "2026-03-16")),
                new AnnualWindow.DatedRun(2026, 12, days("2026-12-15", "2026-12-16"))), twice.datedRuns(2026),
                "March is run 3 and December run 12");
        assertEquals(List.of(3, 6, 9, 12), numbers(quarterly, 2026), "June and September added renumber neither");
        assertEquals(days("2026-12-15", "2026-12-16"), quarterly.run(2026, 12));
        assertEquals(days("2026-03-15", "2026-03-16"), quarterly.run(2026, 3));
        assertNull(quarterly.run(2026, 4), "no April run: the numbers skip");
        assertNull(quarterly.run(2026, 1));
        assertEquals(days("2026-03-15", "2026-03-16"), twice.days(2026), "days(year) is the lowest-numbered run");
        assertEquals(at("2026-12-15T00:00:00Z"), twice.startMs(2026, 12, UTC));
        assertEquals(new AnnualWindow.DatedRun(2026, 12, days("2026-12-15", "2026-12-16")), twice.after(2026, 3));
        assertEquals(new AnnualWindow.DatedRun(2027, 3, days("2027-03-15", "2027-03-16")), twice.after(2026, 12));
        assertEquals(new AnnualWindow.DatedRun(2026, 12, days("2026-12-15", "2026-12-16")),
                twice.nextRun(at("2026-04-01T00:00:00Z"), UTC, 2026));
        assertEquals(new AnnualWindow.DatedRun(2026, 12, days("2026-12-15", "2026-12-16")),
                twice.forcedRun(2026, at("2026-04-01T00:00:00Z"), UTC));
        AnnualWindow everyOther = AnnualWindow.of(new YearRule.Monthly(1, null, 0,
                new YearRule.Cadence(2, LocalDate.of(2026, 2, 1), YearRule.EVERY_MONTH), YearRule.RunLength.ofDays(7)),
                Map.of());
        assertEquals(List.of(2, 4, 6, 8, 10, 12), numbers(everyOther, 2027),
                "every other month from February: the even months, each numbered by its month");
    }

    @Test
    void aWeeklyRunIsNumberedByItsCalendarWeekSoSkippedWeeksLeaveGaps() {
        // Weeks run Monday to Sunday and week 1 holds January 1st. 2026 starts on a Thursday, so its week 1 is
        // January 1st to 4th and its Mondays are January 5th (week 2), the 12th (week 3), the 19th (week 4) ...
        YearRule.Weekly fromTheFifth = new YearRule.Weekly(DayOfWeek.MONDAY,
                new YearRule.Cadence(2, LocalDate.of(2026, 1, 5), YearRule.EVERY_MONTH), YearRule.RunLength.ofDays(1));
        YearRule.Weekly fromTheTwelfth = new YearRule.Weekly(DayOfWeek.MONDAY,
                new YearRule.Cadence(2, LocalDate.of(2026, 1, 12), YearRule.EVERY_MONTH), YearRule.RunLength.ofDays(1));
        AnnualWindow even = AnnualWindow.of(fromTheFifth, Map.of());
        AnnualWindow odd = AnnualWindow.of(fromTheTwelfth, Map.of());
        List<Integer> evenWeeks = new ArrayList<>();
        List<Integer> oddWeeks = new ArrayList<>();
        for (int week = 2; week <= 53; week++) {
            if (week % 2 == 0) {
                evenWeeks.add(week);
            } else {
                oddWeeks.add(week);
            }
        }
        assertEquals(evenWeeks, numbers(even, 2026), "every other Monday from January 5th: weeks 2, 4 ... 52");
        assertEquals(oddWeeks, numbers(odd, 2026),
                "the anchor a week later: weeks 3, 5 ... 53, never renumbered 1, 2 ...");
        for (AnnualWindow window : List.of(even, odd)) {
            for (AnnualWindow.DatedRun run : window.datedRuns(2026)) {
                assertEquals(LocalDate.of(2026, 1, 5).plusWeeks(run.number() - 2L), run.days().first(),
                        "run " + run.number() + " is the Monday of week " + run.number());
            }
        }
        assertNull(odd.run(2026, 2), "an even week has no run");
        assertEquals(new AnnualWindow.DatedRun(2026, 3, days("2026-01-12", "2026-01-12")),
                odd.runContaining(at("2026-01-12T12:00:00Z"), UTC));
        assertEquals(new AnnualWindow.DatedRun(2026, 5, days("2026-01-26", "2026-01-26")), odd.after(2026, 3));
        assertEquals(new AnnualWindow.DatedRun(2026, 53, days("2026-12-28", "2026-12-28")), odd.datedRuns(2026).get(25));
        // 2027 starts on a Friday: week 1 is January 1st to 3rd, and Monday the 11th is in week 3. A listed year's
        // span reaching into 2027 sets aside the run it meets, which keeps its week's number.
        AnnualWindow feast = AnnualWindow.of(fromTheTwelfth, Map.of(2026, span("12-20", "01-12")), 2026);
        assertNotNull(feast);
        assertEquals(List.of(new AnnualWindow.SetAside(2027, 3, days("2027-01-11", "2027-01-11"),
                days("2026-12-20", "2027-01-12"))), feast.setAside(2027), "January 11th 2027 is in week 3");
        assertNull(feast.run(2027, 3));
        assertEquals(new AnnualWindow.DatedRun(2027, 5, days("2027-01-25", "2027-01-25")), feast.datedRuns(2027).get(0),
                "the next odd week stands");
        assertEquals(days("2027-01-25", "2027-01-25"), feast.days(2027), "the year's lowest number still standing");
        assertEquals(new AnnualWindow.DatedRun(2027, 5, days("2027-01-25", "2027-01-25")), feast.after(2026, 1),
                "after the feast, by the dates");
    }

    @Test
    void aRunCrossingTheNewYearIsNumberedFromItsStart() {
        // 2026 starts on a Thursday, so Thursday December 31st 2026 falls in the year's week 53.
        AnnualWindow decemberThursdays = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.THURSDAY,
                new YearRule.Cadence(1, null, Set.of(Month.DECEMBER)), YearRule.RunLength.ofDays(3)), Map.of());
        assertEquals(List.of(49, 50, 51, 52, 53), numbers(decemberThursdays, 2026));
        assertEquals(new AnnualWindow.DatedRun(2026, 53, days("2026-12-31", "2027-01-02")),
                decemberThursdays.runContaining(at("2027-01-01T12:00:00Z"), UTC), "run 53 of the year it starts in");
        // 2012 is a leap year starting on a Sunday: that Sunday alone is week 1, and Monday December 31st is week 54.
        AnnualWindow mondays = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.MONDAY, YearRule.Cadence.ALWAYS,
                YearRule.RunLength.ofDays(1)), Map.of());
        assertNull(mondays.run(2012, 1), "week 1 of 2012 holds no Monday");
        assertEquals(days("2012-01-02", "2012-01-02"), mondays.run(2012, 2));
        assertEquals(days("2012-12-31", "2012-12-31"), mondays.run(2012, 54));
        AnnualWindow sundays = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.SUNDAY, YearRule.Cadence.ALWAYS,
                YearRule.RunLength.ofDays(1)), Map.of());
        assertEquals(days("2012-01-01", "2012-01-01"), sundays.run(2012, 1));
        AnnualWindow yearEnd = AnnualWindow.of(new YearRule.Monthly(28, null, 0,
                new YearRule.Cadence(1, null, Set.of(Month.JUNE, Month.DECEMBER)), YearRule.RunLength.ofDays(7)),
                Map.of());
        assertEquals(new AnnualWindow.DatedRun(2026, 12, days("2026-12-28", "2027-01-03")),
                yearEnd.runContaining(at("2027-01-02T12:00:00Z"), UTC), "December's run, though it ends in January");
        assertEquals(List.of(6, 12), numbers(yearEnd, 2027));
        // A weekday falls in each calendar week once at most, so a weekly rule's runs take one week each, in a row,
        // from week 1 when the weekday falls between January 1st and the first Sunday, else from week 2.
        for (DayOfWeek weekday : DayOfWeek.values()) {
            AnnualWindow weekly = AnnualWindow.of(new YearRule.Weekly(weekday, YearRule.Cadence.ALWAYS,
                    YearRule.RunLength.ofDays(1)), Map.of());
            for (int year = 2010; year <= 2034; year++) {
                List<Integer> numbers = numbers(weekly, year);
                boolean inWeekOne = weekday.getValue() >= LocalDate.of(year, 1, 1).getDayOfWeek().getValue();
                assertEquals(inWeekOne ? 1 : 2, numbers.get(0), weekday + " " + year + ": its first week");
                for (int i = 1; i < numbers.size(); i++) {
                    assertEquals(numbers.get(i - 1) + 1, numbers.get(i), weekday + " " + year + ": one run a week");
                }
                assertTrue(numbers.get(numbers.size() - 1) <= 54, weekday + " " + year + ": 54 weeks at most");
            }
        }
    }

    // Only a repeating rule numbers a run by its month or week: a rule with one run a year numbers it 1, and a list
    // of spans (the rule's, or a Years entry's) numbers each by its place in the list, a December span included.
    @Test
    void aOnceAYearRunIsRun1AndAListOfSpansKeepsItsWrittenOrder() {
        for (YearRule once : List.<YearRule>of(span("12-20", "01-05"), new YearRule.Easter(10, 7),
                new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 4, 6, 5))) {
            assertEquals(List.of(1), numbers(AnnualWindow.of(once, Map.of()), 2026), once.describe() + " is run 1");
        }
        assertEquals(List.of(1), numbers(AnnualWindow.of(null, Map.of(2026, span("12-01", "12-07"))), 2026),
                "a Years entry's one run is run 1");
        AnnualWindow spans = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("12-01", "12-07"), span("03-01", "03-07"))), Map.of());
        assertEquals(List.of(new AnnualWindow.DatedRun(2026, 1, days("2026-12-01", "2026-12-07")),
                new AnnualWindow.DatedRun(2026, 2, days("2026-03-01", "2026-03-07"))), spans.datedRuns(2026),
                "the December span written first is run 1");
        AnnualWindow beside = AnnualWindow.of(new YearRule.Monthly(1, null, 0, YearRule.Cadence.ALWAYS,
                YearRule.RunLength.ofDays(7)), Map.of(2027, new YearRule.FixedRuns(
                List.of(span("12-01", "12-07"), span("03-01", "03-07")))), 2026);
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), numbers(beside, 2026));
        assertEquals(List.of(1, 2), numbers(beside, 2027), "a listed year's spans keep their places beside a monthly rule");
        assertEquals(days("2027-12-01", "2027-12-07"), beside.run(2027, 1));
    }

    @Test
    void aRunWithATimeOfDayIsFoundByItsHours() {
        AnnualWindow contest = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.SUNDAY, YearRule.Cadence.ALWAYS,
                new YearRule.RunLength(1, LocalTime.of(14, 0), Duration.ofHours(2), false)), Map.of(), 2026);
        assertNotNull(contest);
        AnnualWindow.DatedRun run = contest.runContaining(at("2026-10-11T15:00:00Z"), UTC);
        assertNotNull(run);
        assertEquals(41, run.number(), "October 11th is the Sunday that ends the year's forty-first week");
        assertEquals(at("2026-10-11T16:00:00Z"), run.days().endMs(UTC));
        assertNull(contest.runContaining(at("2026-10-11T16:00:00Z"), UTC), "over at four");
        assertNull(contest.runContaining(at("2026-10-11T13:59:00Z"), UTC), "not yet at a minute to two");
        assertEquals(at("2026-10-18T14:00:00Z"), contest.nextStartMs(at("2026-10-11T16:00:00Z"), UTC, 2026));
    }

    // A wall-clock time inside a spring-forward gap stands for the instant the gap's length later, so a run
    // starting there could end no later than it starts; it lasts its wall-clock length from its start instead.
    @Test
    void aRunStartingInASpringForwardGapStillEndsAfterItStarts() {
        ZoneId newYork = ZoneId.of("America/New_York");
        // Sunday March 8th 2026 is week 10 of the year, and New York's clocks go from 02:00 to 03:00 (07:00 UTC).
        AnnualWindow twoAm = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.SUNDAY, YearRule.Cadence.ALWAYS,
                new YearRule.RunLength(1, LocalTime.of(2, 0), Duration.ofHours(1), false)), Map.of(), 2026);
        assertEquals(at("2026-03-08T07:00:00Z"), twoAm.startMs(2026, 10, newYork), "02:00 is read as 03:00");
        assertEquals(at("2026-03-08T08:00:00Z"), twoAm.endMs(2026, 10, newYork), "and the run lasts its hour");
        assertEquals(10, twoAm.runContaining(at("2026-03-08T07:30:00Z"), newYork).number(), "found going on");
        AnnualWindow halfPast = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.SUNDAY, YearRule.Cadence.ALWAYS,
                new YearRule.RunLength(1, LocalTime.of(2, 30), Duration.ofMinutes(30), false)), Map.of(), 2026);
        assertEquals(at("2026-03-08T07:30:00Z"), halfPast.startMs(2026, 10, newYork));
        assertEquals(at("2026-03-08T08:00:00Z"), halfPast.endMs(2026, 10, newYork),
                "half an hour, never ending before it starts");
        // Sunday November 1st 2026 has 01:00 to 02:00 twice; 01:30 is read as the first, so the run is two hours.
        AnnualWindow fallBack = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.SUNDAY, YearRule.Cadence.ALWAYS,
                new YearRule.RunLength(1, LocalTime.of(1, 30), Duration.ofHours(1), false)), Map.of(), 2026);
        AnnualWindow.DatedRun twice = fallBack.runContaining(at("2026-11-01T06:00:00Z"), newYork);
        assertNotNull(twice);
        assertEquals(at("2026-11-01T05:30:00Z"), twice.days().startMs(newYork));
        assertEquals(at("2026-11-01T07:30:00Z"), twice.days().endMs(newYork), "a positive length");
    }

    // The run after one the year does not have: a monthly or weekly rule searches from where that number falls
    // (the start of its month or calendar week), a run set aside from its own days, and a list of spans, whose
    // places name no date, from the next year's earliest run.
    @Test
    void theRunAfterOneTheYearLacksIsSoughtFromWhereThatRunWouldFall() {
        AnnualWindow mayDropped = AnnualWindow.of(new YearRule.Monthly(15, null, 0,
                new YearRule.Cadence(1, null, Set.of(Month.MARCH, Month.DECEMBER)), YearRule.RunLength.ofDays(2)),
                Map.of());
        assertEquals(new AnnualWindow.DatedRun(2026, 12, days("2026-12-15", "2026-12-16")), mayDropped.after(2026, 5),
                "May dropped from Months [3, 5, 12] after a player finished run 5: December's run comes next");
        assertEquals(new AnnualWindow.DatedRun(2026, 3, days("2026-03-15", "2026-03-16")), mayDropped.after(2026, 1));
        AnnualWindow oddWeeks = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.MONDAY,
                new YearRule.Cadence(2, LocalDate.of(2026, 1, 12), YearRule.EVERY_MONTH), YearRule.RunLength.ofDays(1)),
                Map.of());
        assertEquals(new AnnualWindow.DatedRun(2026, 3, days("2026-01-12", "2026-01-12")), oddWeeks.after(2026, 2));
        assertEquals(new AnnualWindow.DatedRun(2026, 53, days("2026-12-28", "2026-12-28")), oddWeeks.after(2026, 52));
        assertEquals(new AnnualWindow.DatedRun(2027, 3, days("2027-01-11", "2027-01-11")), oddWeeks.after(2026, 54),
                "2026 has no week 54: the next year's earliest");
        AnnualWindow overlapping = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("09-20", "09-26"), span("09-01", "09-25"), span("10-10", "10-12"))), Map.of());
        assertEquals(new AnnualWindow.DatedRun(2026, 1, days("2026-09-20", "2026-09-26")), overlapping.after(2026, 2),
                "after the span set aside (September 1st to 25th), by its own days");
        AnnualWindow crossing = AnnualWindow.of(
                new YearRule.FixedRuns(List.of(span("01-05", "01-11"), span("06-01", "06-07"))),
                Map.of(2027, new YearRule.FixedRuns(List.of(span("12-20", "01-08")))), 2026);
        assertEquals(new AnnualWindow.DatedRun(2028, 2, days("2028-06-01", "2028-06-07")), crossing.after(2028, 1),
                "January 2028's run, set aside under the run from December, is followed by June's");
        AnnualWindow spans = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("09-20", "09-26"), span("04-10", "04-16"))), Map.of());
        assertEquals(new AnnualWindow.DatedRun(2027, 2, days("2027-04-10", "2027-04-16")), spans.after(2026, 3),
                "a place the list lacks names no date: the next year's earliest");
    }

    @Test
    void aForceRunsTheYearsFirstRunNotYetOverElseItsLast() {
        AnnualWindow window = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("04-10", "04-16"), span("09-20", "09-26"))), Map.of());
        assertEquals(1, window.forcedRun(2026, at("2026-03-01T12:00:00Z"), UTC).number(), "before spring: spring");
        assertEquals(1, window.forcedRun(2026, at("2026-04-12T12:00:00Z"), UTC).number());
        assertEquals(2, window.forcedRun(2026, at("2026-06-01T12:00:00Z"), UTC).number(),
                "spring is over: the autumn run comes forward");
        assertEquals(2, window.forcedRun(2026, at("2026-10-15T12:00:00Z"), UTC).number(),
                "every run is over: the year's last again, never a run of another year");
        AnnualWindow written = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("09-20", "09-26"), span("04-10", "04-16"))), Map.of());
        assertEquals(new AnnualWindow.DatedRun(2026, 2, days("2026-04-10", "2026-04-16")),
                written.forcedRun(2026, at("2026-03-01T12:00:00Z"), UTC), "chosen by the dates, numbered as written");
        assertEquals(new AnnualWindow.DatedRun(2026, 1, days("2026-09-20", "2026-09-26")),
                written.forcedRun(2026, at("2026-10-15T12:00:00Z"), UTC), "the year's last by its dates");
        AnnualWindow dated = AnnualWindow.of(null, Map.of(2026, span("06-01", "06-07")));
        assertNull(dated.forcedRun(2027, at("2027-06-01T12:00:00Z"), UTC), "a year with no run has nothing to force");
    }

    // Runs of one event never overlap (the maintainer's ruling): the run set aside is the later one in the order
    // written, and its number is never handed to another run.
    @Test
    void aRunThatMeetsOneWrittenBeforeItIsSetAsideAndItsNumberIsNotReused() {
        AnnualWindow window = AnnualWindow.of(new YearRule.FixedRuns(
                List.of(span("09-20", "09-26"), span("09-01", "09-25"), span("10-10", "10-12"))), Map.of());
        assertEquals(List.of(days("2026-09-20", "2026-09-26"), days("2026-10-10", "2026-10-12")), window.runs(2026),
                "the first written is kept though the second starts sooner");
        assertEquals(List.of(new AnnualWindow.SetAside(2026, 2, days("2026-09-01", "2026-09-25"),
                days("2026-09-20", "2026-09-26"))), window.setAside(2026));
        assertNull(window.run(2026, 2), "a run set aside keeps its number to itself");
        assertEquals(days("2026-10-10", "2026-10-12"), window.run(2026, 3), "and the run after it keeps its own");
        assertEquals(List.of(new AnnualWindow.DatedRun(2026, 1, days("2026-09-20", "2026-09-26")),
                new AnnualWindow.DatedRun(2026, 3, days("2026-10-10", "2026-10-12"))), window.datedRuns(2026));
        assertNull(window.runContaining(at("2026-09-05T12:00:00Z"), UTC), "the run set aside never runs");
        assertEquals(new AnnualWindow.DatedRun(2026, 3, days("2026-10-10", "2026-10-12")), window.after(2026, 1));
    }

    @Test
    void aRunCrossingTheNewYearSetsAsideTheNextYearsRunsItMeets() {
        AnnualWindow window = AnnualWindow.of(
                new YearRule.FixedRuns(List.of(span("01-05", "01-11"), span("06-01", "06-07"))),
                Map.of(2027, new YearRule.FixedRuns(List.of(span("12-20", "01-08")))), 2026);
        assertNotNull(window);
        assertEquals(List.of(days("2027-12-20", "2028-01-08")), window.runs(2027), "a listed year dates its whole year");
        assertEquals(List.of(days("2028-06-01", "2028-06-07")), window.runs(2028),
                "January 5th 2028 falls inside the run that began in December, so that run is set aside");
        assertEquals(List.of(new AnnualWindow.SetAside(2028, 1, days("2028-01-05", "2028-01-11"),
                days("2027-12-20", "2028-01-08"))), window.setAside(2028));
        assertEquals(List.of(new AnnualWindow.DatedRun(2028, 2, days("2028-06-01", "2028-06-07"))),
                window.datedRuns(2028), "the June run keeps its number");
        assertEquals(days("2028-06-01", "2028-06-07"), window.days(2028), "the year's first run still standing");
        assertEquals(new AnnualWindow.DatedRun(2027, 1, days("2027-12-20", "2028-01-08")),
                window.runContaining(at("2028-01-06T12:00:00Z"), UTC), "the run going on is 2027's");
    }

    @Test
    void nothingBeforeTheFloorIsDatedSoARunThatNeverHappensSetsNothingAside() {
        YearRule fairs = new YearRule.FixedRuns(List.of(span("01-02", "01-08"), span("12-20", "01-05")));
        AnnualWindow fromFirstYear = AnnualWindow.of(fairs, Map.of(), 2026);
        assertNotNull(fromFirstYear);
        assertEquals(List.of(days("2026-01-02", "2026-01-08"), days("2026-12-20", "2027-01-05")),
                fromFirstYear.runs(2026), "no 2025 run happens, so none reaches into 2026");
        assertTrue(fromFirstYear.runs(2025).isEmpty());
        assertEquals(List.of(days("2027-12-20", "2028-01-05")), fromFirstYear.runs(2027),
                "the 2026 run crossing into January sets 2027's January run aside");
        assertEquals(List.of(days("2026-12-20", "2027-01-05")), AnnualWindow.of(fairs, Map.of()).runs(2026),
                "with no floor the 2025 run is dated, and meets 2026's January run");
    }

    @Test
    void aListedYearWithNoRunsHasNoneAndAnInvalidRuleOrYearIsRefused() {
        AnnualWindow window = AnnualWindow.of(span("06-01", "06-07"),
                Map.of(2027, new YearRule.FixedRuns(List.of())), 2026);
        assertNotNull(window);
        assertFalse(window.hasRun(2027));
        assertEquals(Integer.valueOf(2028), window.nextRunYear(2027));
        assertEquals(new AnnualWindow.DatedRun(2028, 1, days("2028-06-01", "2028-06-07")), window.after(2026, 1));
        assertEquals(at("2028-06-01T00:00:00Z"), window.nextStartMs(at("2027-01-01T00:00:00Z"), UTC, 2026));
        assertThrows(IllegalArgumentException.class, () -> AnnualWindow.of(new YearRule.Easter(0, 350), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> AnnualWindow.of(null,
                Map.of(2028, new YearRule.Fixed(MonthDay.of(2, 29), MonthDay.of(2, 28)))));
    }
}
