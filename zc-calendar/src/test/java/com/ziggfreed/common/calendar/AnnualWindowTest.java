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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A window of runs: both days in, crossing the new year, counted in a zone; several runs a year numbered by the
 * order the rule gives them, a run that meets an earlier-written one set aside, and nothing dated before the floor.
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

    @Test
    void aRunWithATimeOfDayIsFoundByItsHours() {
        AnnualWindow contest = AnnualWindow.of(new YearRule.Weekly(DayOfWeek.SUNDAY, YearRule.Cadence.ALWAYS,
                new YearRule.RunLength(1, LocalTime.of(14, 0), Duration.ofHours(2), false)), Map.of(), 2026);
        assertNotNull(contest);
        AnnualWindow.DatedRun run = contest.runContaining(at("2026-10-11T15:00:00Z"), UTC);
        assertNotNull(run);
        assertEquals(41, run.number(), "the year's forty-first Sunday");
        assertEquals(at("2026-10-11T16:00:00Z"), run.days().endMs(UTC));
        assertNull(contest.runContaining(at("2026-10-11T16:00:00Z"), UTC), "over at four");
        assertNull(contest.runContaining(at("2026-10-11T13:59:00Z"), UTC), "not yet at a minute to two");
        assertEquals(at("2026-10-18T14:00:00Z"), contest.nextStartMs(at("2026-10-11T16:00:00Z"), UTC, 2026));
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
