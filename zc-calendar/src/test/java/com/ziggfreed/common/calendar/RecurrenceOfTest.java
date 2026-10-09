package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.YearRule.Cadence;
import com.ziggfreed.common.calendar.YearRule.RunLength;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.Recurrence;

/**
 * The calendar's rules as zc-core's neutral {@link Recurrence}, which a reader that never sees the calendar says in
 * words: a monthly or weekly rule that dates several runs a year is one; a rule with one run a year, several spans of
 * month-days, and a year whose days a Years entry sets out are not.
 */
class RecurrenceOfTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 5);

    private final CalendarService service =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance());

    @BeforeEach
    void start() {
        CalendarFixtures.reset();
    }

    @AfterEach
    void clear() {
        CalendarFixtures.reset();
    }

    private static void load(String id, String json) {
        CalendarFixtures.loadEvents(Map.of(id.toLowerCase(Locale.ROOT), CalendarFixtures.event(id, json)));
    }

    // ---- the examples, rule by rule ----

    @Test
    void theTravelingFairIsTheFirstSundayOfEveryMonthForSevenDays() {
        YearRule fair = new YearRule.Monthly(0, DayOfWeek.SUNDAY, 1, Cadence.ALWAYS, RunLength.ofDays(7));
        assertEquals(Recurrence.Monthly.onWeekday(1, DayOfWeek.SUNDAY, 1, Set.of(), Recurrence.Length.days(7)),
                RecurrenceOf.rule(fair));
        YearRule lastFriday = new YearRule.Monthly(0, DayOfWeek.FRIDAY, YearRule.Weekday.LAST, Cadence.ALWAYS,
                RunLength.ofDays(1));
        assertEquals(Recurrence.LAST, ((Recurrence.Monthly) RecurrenceOf.rule(lastFriday)).nth(),
                "the last weekday of the month stays the last");
    }

    @Test
    void aQuarterlyMarketIsTheFifteenthOfItsFourMonthsForTwoDays() {
        Cadence quarters = new Cadence(1, null, Set.of(Month.MARCH, Month.JUNE, Month.SEPTEMBER, Month.DECEMBER));
        YearRule market = new YearRule.Monthly(15, null, 0, quarters, RunLength.ofDays(2));
        Recurrence said = RecurrenceOf.rule(market);
        assertEquals(Recurrence.Monthly.onDay(15, 1, quarters.months(), Recurrence.Length.days(2)), said);
        assertEquals(List.of(Month.MARCH, Month.JUNE, Month.SEPTEMBER, Month.DECEMBER), List.copyOf(said.months()));
    }

    @Test
    void aRuleEveryOtherMonthKeepsItsEveryAndNamesNoMonths() {
        YearRule other = new YearRule.Monthly(1, null, 0, new Cadence(2, ANCHOR, YearRule.EVERY_MONTH),
                RunLength.ofDays(7));
        assertEquals(Recurrence.Monthly.onDay(1, 2, Set.of(), Recurrence.Length.days(7)), RecurrenceOf.rule(other),
                "the anchor dates the runs; how often they come is what a reader says");
    }

    @Test
    void theFishingContestIsEverySundayFromTwoForTwoHours() {
        YearRule contest = new YearRule.Weekly(DayOfWeek.SUNDAY, Cadence.ALWAYS,
                new RunLength(0, LocalTime.of(14, 0), Duration.ofHours(2), false));
        assertEquals(new Recurrence.Weekly(DayOfWeek.SUNDAY, 1, Set.of(),
                Recurrence.Length.timed(LocalTime.of(14, 0), Duration.ofHours(2))), RecurrenceOf.rule(contest));
    }

    @Test
    void aWeeklyRuleADayLongAndOneEveryOtherWeekForAWeek() {
        YearRule saturdays = new YearRule.Weekly(DayOfWeek.SATURDAY, Cadence.ALWAYS, RunLength.ofDays(1));
        assertEquals(new Recurrence.Weekly(DayOfWeek.SATURDAY, 1, Set.of(), Recurrence.Length.days(1)),
                RecurrenceOf.rule(saturdays));

        YearRule otherMondays = new YearRule.Weekly(DayOfWeek.MONDAY, new Cadence(2, ANCHOR, YearRule.EVERY_MONTH),
                RunLength.ofDays(7));
        assertEquals(new Recurrence.Weekly(DayOfWeek.MONDAY, 2, Set.of(), Recurrence.Length.days(7)),
                RecurrenceOf.rule(otherMondays));
    }

    @Test
    void aRunUntilTheNextKeepsOnlyItsTimeOfDay() {
        YearRule monthly = new YearRule.Monthly(1, null, 0, Cadence.ALWAYS, new RunLength(3, null, null, true));
        assertEquals(Recurrence.Monthly.onDay(1, 1, Set.of(), Recurrence.Length.untilNext(null)),
                RecurrenceOf.rule(monthly));
        YearRule evenings = new YearRule.Weekly(DayOfWeek.FRIDAY, Cadence.ALWAYS,
                new RunLength(1, LocalTime.of(18, 0), Duration.ofHours(2), true));
        assertEquals(Recurrence.Length.untilNext(LocalTime.of(18, 0)), RecurrenceOf.rule(evenings).length(),
                "until the next wins over a length, as the calendar dates it");
    }

    // ---- what is no recurrence ----

    @Test
    void aRuleWithOneRunAYearIsNoRecurrence() {
        assertNull(RecurrenceOf.rule(null));
        assertNull(RecurrenceOf.rule(new YearRule.Fixed(MonthDay.of(10, 1), MonthDay.of(11, 3))));
        assertNull(RecurrenceOf.rule(new YearRule.Easter(10, 7)));
        assertNull(RecurrenceOf.rule(new YearRule.Weekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 4, 0, 3)));
        assertNull(RecurrenceOf.rule(new YearRule.Monthly(15, null, 0, new Cadence(1, null, Set.of(Month.OCTOBER)),
                RunLength.ofDays(2))), "a monthly rule in one month comes round once a year");
        assertNull(RecurrenceOf.rule(new YearRule.Monthly(1, null, 0, new Cadence(12, ANCHOR, YearRule.EVERY_MONTH),
                RunLength.ofDays(2))), "every twelve months is once a year");
        assertNull(RecurrenceOf.rule(new YearRule.FixedRuns(List.of(
                new YearRule.Fixed(MonthDay.of(4, 10), MonthDay.of(4, 16)),
                new YearRule.Fixed(MonthDay.of(9, 10), MonthDay.of(9, 16))))),
                "several spans of month-days are dated spans, said by their days");
    }

    @Test
    void aYearWhoseDaysAYearsEntrySetsOutIsNoRecurrence() {
        YearRule monthly = new YearRule.Monthly(0, DayOfWeek.SUNDAY, 1, Cadence.ALWAYS, RunLength.ofDays(7));
        AnnualWindow window = AnnualWindow.of(monthly,
                Map.of(2031, new YearRule.Fixed(MonthDay.of(4, 1), MonthDay.of(4, 20))));
        assertNotNull(RecurrenceOf.rule(window.rule(2030)), "the rule dates 2030");
        assertNull(RecurrenceOf.rule(window.rule(2031)), "2031's days are its Years entry's");
        assertNotNull(RecurrenceOf.rule(window.rule(2032)), "and the rule dates the years after it again");
    }

    // ---- through the calendar ----

    @Test
    void theCalendarSaysHowItsMonthlyAndWeeklyEventsComeRound() {
        CalendarFixtures.loadEvents(Map.of(
                "traveling_fair", CalendarFixtures.event("Traveling_Fair", """
                        { "Window": { "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 } },
                          "FirstYear": 2026 }
                        """),
                "fishing_contest", CalendarFixtures.event("Fishing_Contest", """
                        { "Window": { "Rule": { "Type": "Weekly", "Weekday": "Sunday", "At": "14:00",
                                                "Length": "PT2H" } }, "FirstYear": 2026 }
                        """)));
        long now = at("2026-10-07T12:00:00Z");
        assertEquals(Recurrence.Monthly.onWeekday(1, DayOfWeek.SUNDAY, 1, Set.of(), Recurrence.Length.days(7)),
                service.recurrence("traveling_fair", now));
        assertEquals(new Recurrence.Weekly(DayOfWeek.SUNDAY, 1, Set.of(),
                Recurrence.Length.timed(LocalTime.of(14, 0), Duration.ofHours(2))),
                service.recurrence("fishing_contest", now));
        assertNull(service.recurrence("no_such_event", now));
    }

    @Test
    void aOnceAYearEventAndAYearOfItsOwnDaysHaveNoRecurrenceFromTheCalendar() {
        load("Hallows_Eve", CalendarFixtures.HALLOWS_EVE);
        assertNull(service.recurrence("hallows_eve", at("2026-10-07T12:00:00Z")));

        load("Market", """
                { "Window": { "Rule": { "Type": "Monthly", "Day": 15, "Days": 2 },
                              "Years": { "2031": { "Start": "04-01", "End": "04-20" } } }, "FirstYear": 2026 }
                """);
        assertNotNull(service.recurrence("market", at("2030-06-01T12:00:00Z")));
        assertNull(service.recurrence("market", at("2031-06-01T12:00:00Z")), "2031 is set out by its own days");
        assertNotNull(service.recurrence("market", at("2024-06-01T12:00:00Z")),
                "before its first year it says the rule that will date its first run");
    }

    // The rule a reader is told is the one dating the year the event is IN (OccurrenceSource.recurrence): a December
    // run still going on in January is its starting year's, so it answers that year's rule, never the new year's.
    @Test
    void aDecemberRunLiveInJanuaryAnswersItsOwnYearsRule() {
        load("Market", """
                { "Window": { "Rule": { "Type": "Monthly", "Day": 28, "Days": 7 },
                              "Years": { "2027": { "Start": "04-01", "End": "04-20" } } }, "FirstYear": 2026 }
                """);
        long january2 = at("2027-01-02T12:00:00Z");
        Occurrence december = service.live("market", january2);
        assertNotNull(december, "December 28th's run lasts to January 3rd");
        assertEquals(2026, december.year());

        assertEquals(Recurrence.Monthly.onDay(28, 1, Set.of(), Recurrence.Length.days(7)),
                service.recurrence("market", january2), "the run going on is 2026's, so 2026's rule says how it comes");
        assertNull(service.recurrence("market", at("2027-01-10T12:00:00Z")),
                "once that run is over, 2027 is the year it is in, and its days are its own");
    }

    // ---- what the Almanac reads beside the rule: the run on now and the next ----

    // Review Focus 2 on the Almanac's dates lines: an admin's force on runs a run with its own days, and through it
    // the rule stays the same and the next run is the one after the forced run (zc-almanac's AlmanacPagePlanTest
    // paints the hero from these very answers).
    @Test
    void aForcedRunKeepsItsRuleAndTheRunAfterItIsNext() {
        load("Traveling_Fair", """
                { "Window": { "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 } },
                  "FirstYear": 2026 }
                """);
        Recurrence fair = Recurrence.Monthly.onWeekday(1, DayOfWeek.SUNDAY, 1, Set.of(), Recurrence.Length.days(7));
        long between = at("2026-09-30T12:00:00Z");
        Occurrence forced = service.forceOn("Traveling_Fair", between);
        assertNotNull(forced);
        assertEquals("2026#10", forced.label(), "October's run brought forward");
        assertEquals(at("2026-10-04T00:00:00Z"), forced.startMs(), "with its own days, still ahead");
        assertEquals(forced, service.live("traveling_fair", between));
        Occurrence next = service.next("traveling_fair", between);
        assertNotNull(next);
        assertEquals("2026#11", next.label(), "while the forced run is on, the next is the one after it");
        assertEquals(at("2026-11-01T00:00:00Z"), next.startMs());
        assertEquals(at("2026-11-08T00:00:00Z"), next.endMs());
        assertEquals(fair, service.recurrence("traveling_fair", between), "a force changes no rule");

        load("Fishing_Contest", """
                { "Window": { "Rule": { "Type": "Weekly", "Weekday": "Sunday", "At": "14:00", "Length": "PT2H" } },
                  "FirstYear": 2026 }
                """);
        long newYearsEve = at("2026-12-31T12:00:00Z");
        Occurrence replay = service.forceOn("Fishing_Contest", newYearsEve);
        assertNotNull(replay);
        assertEquals(at("2026-12-27T14:00:00Z"), replay.startMs(), "after the year's last Sunday, that run again");
        assertEquals(replay, service.live("fishing_contest", newYearsEve));
        Occurrence january = service.next("fishing_contest", newYearsEve);
        assertNotNull(january);
        assertEquals(at("2027-01-03T14:00:00Z"), january.startMs(), "and the new year's first Sunday next");
        assertEquals(at("2027-01-03T16:00:00Z"), january.endMs());
        assertEquals(new Recurrence.Weekly(DayOfWeek.SUNDAY, 1, Set.of(),
                Recurrence.Length.timed(LocalTime.of(14, 0), Duration.ofHours(2))),
                service.recurrence("fishing_contest", newYearsEve));
    }

    // A Years entry's Skip leaves a run out of one year and the rule as it is: the Almanac still says the rule, and
    // the run it names next is always one the year keeps.
    @Test
    void aYearThatSkipsARunSaysTheSameRuleAndItsNextRunIsOneTheYearKeeps() {
        load("Traveling_Fair", """
                { "Window": { "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 },
                              "Years": { "2026": { "Skip": [11] } } }, "FirstYear": 2026 }
                """);
        long october = at("2026-10-20T12:00:00Z");
        assertEquals(Recurrence.Monthly.onWeekday(1, DayOfWeek.SUNDAY, 1, Set.of(), Recurrence.Length.days(7)),
                service.recurrence("traveling_fair", october), "the rule still dates the year");
        Occurrence next = service.next("traveling_fair", october);
        assertNotNull(next);
        assertEquals("2026#12", next.label(), "November's run is skipped, so December's is next");
        assertEquals(at("2026-12-06T00:00:00Z"), next.startMs());
    }
}
