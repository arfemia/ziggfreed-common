package com.ziggfreed.common.calendar.command;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.CalendarForces;
import com.ziggfreed.common.calendar.CalendarService;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.calendar.command.CalendarStatusLines.Line;
import com.ziggfreed.common.occurrence.Occurrence;

/** What an administrator reads about an event, as keys and raw data: running, waiting, forced, off, broken. */
class CalendarStatusLinesTest {

    private static final String EASTER_HUNT = """
            { "Window": { "Start": "04-01", "End": "04-10",
                          "Rule": { "Type": "Easter", "Before": 10, "After": 7 } }, "FirstYear": 2027 }
            """;

    private final CalendarService service =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance());

    @BeforeEach
    void load() {
        CalendarFixtures.reset();
        CalendarFixtures.loadDesignEvents();
    }

    @AfterEach
    void reset() {
        CalendarFixtures.reset();
    }

    @Test
    void aRunningEventSaysItsYearAndItsLastDay() {
        assertEquals(new Line("row.live", List.of("hallows_eve", "2026", "2026-11-03")),
                CalendarStatusLines.row(service, "Hallows_Eve", at("2026-10-02T12:00:00Z")));
    }

    @Test
    void aWaitingEventSaysWhenItNextStarts() {
        assertEquals(new Line("row.waiting", List.of("harvest_moon", "2026-10-29")),
                CalendarStatusLines.row(service, "harvest_moon", at("2026-10-02T12:00:00Z")));
    }

    @Test
    void forcedAndStoppedEventsSaySo() {
        long october = at("2026-10-02T12:00:00Z");
        service.forceOn("harvest_moon", october);
        assertEquals("row.live.forced", CalendarStatusLines.row(service, "harvest_moon", october).key());
        CalendarForces.getInstance().forceOff("hallows_eve");
        assertEquals(new Line("row.stopped", List.of("hallows_eve")), CalendarStatusLines.row(service, "hallows_eve", october));
    }

    @Test
    void aForcedRunSaysItRunsThroughTheForcesOwnEnd() {
        long october = at("2026-10-02T12:00:00Z");
        service.forceOn("harvest_moon", october);
        assertEquals(new Line("row.live.forced", List.of("harvest_moon", "2026", "2026-10-31")),
                CalendarStatusLines.row(service, "harvest_moon", october), "brought forward: through its own last day");
        long december = at("2026-12-10T12:00:00Z");
        service.forceOn("hallows_eve", december);
        assertEquals(new Line("row.live.forced", List.of("hallows_eve", "2026", "2027-01-13")),
                CalendarStatusLines.row(service, "hallows_eve", december),
                "run again in December: its usual 34 days from the force, through January 13th");
        assertEquals(new Line("row.waiting", List.of("harvest_moon", "2027-10-29")),
                CalendarStatusLines.row(service, "harvest_moon", at("2026-11-01T00:00:00Z")),
                "a force whose run is over reads as the dates, before the tick's look clears it");
    }

    @Test
    void aSwitchedOffEventAndAnUnknownOneAreTold() {
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertEquals(new Line("row.off", List.of("hallows_eve")), CalendarStatusLines.row(service, "hallows_eve", 0L));
        assertEquals(new Line("row.unknown", List.of("No_Such_Event")), CalendarStatusLines.row(service, "No_Such_Event", 0L));
    }

    @Test
    void anEventThatCannotRunSaysWhy() {
        CalendarFixtures.loadEvents(Map.of("broken", CalendarFixtures.event("Broken",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"10-02\" } }")));
        assertEquals(new Line("row.broken", List.of("broken", "FIRST_YEAR_MISSING")),
                CalendarStatusLines.row(service, "broken", 0L));
    }

    @Test
    void statusAddsTheDatesTheFirstYearAndTheRunsSoFar() {
        List<Line> lines = CalendarStatusLines.detail(service, "hallows_eve", at("2026-10-02T12:00:00Z"));
        assertEquals(List.of("row.live", "status.window", "status.first", "status.history"),
                lines.stream().map(Line::key).toList());
        assertEquals(List.of("10-01..11-03", "UTC"), lines.get(1).args());
        assertEquals(List.of("2026"), lines.get(2).args());
        assertEquals(List.of("2026"), lines.get(3).args());
    }

    @Test
    void aMovingWindowSaysItsRuleAndTheDaysOfTheRunItFrames() {
        CalendarFixtures.loadEvents(Map.of("egg_hunt", CalendarFixtures.event("Egg_Hunt", EASTER_HUNT)));
        List<Line> lines = CalendarStatusLines.detail(service, "egg_hunt", at("2027-01-10T12:00:00Z"));
        assertEquals(List.of("row.waiting", "status.window.moving", "status.run", "status.first",
                "status.history.none", "status.note"), lines.stream().map(Line::key).toList());
        assertEquals(List.of("Easter -10..+7", "UTC"), lines.get(1).args(), "never 'every year'");
        assertEquals(List.of("2027", "2027-03-18", "2027-04-04"), lines.get(2).args(),
                "the run it frames is the next one, with its own days");
        assertEquals(List.of("START_END_IGNORED"), lines.get(5).args());
    }

    @Test
    void aWindowOfPerYearDaysThatRanOutSaysSoRatherThanOff() {
        CalendarFixtures.loadEvents(Map.of("fair", CalendarFixtures.event("Fair",
                "{ \"Window\": { \"Years\": { \"2026\": { \"Start\": \"06-01\", \"End\": \"06-07\" } } },"
                        + " \"FirstYear\": 2026 }")));
        assertEquals(new Line("row.done", List.of("fair")),
                CalendarStatusLines.row(service, "fair", at("2027-01-01T00:00:00Z")));
    }

    @Test
    void aWindowOfPerYearDaysThatRanOutFramesItsLastRun() {
        CalendarFixtures.loadEvents(Map.of("fair", CalendarFixtures.event("Fair",
                "{ \"Window\": { \"Years\": { \"2026\": { \"Start\": \"06-01\", \"End\": \"06-07\" } } },"
                        + " \"FirstYear\": 2026 }")));
        List<Line> lines = CalendarStatusLines.detail(service, "fair", at("2027-01-01T00:00:00Z"));
        assertEquals(List.of("row.done", "status.window.moving", "status.run", "status.first", "status.history"),
                lines.stream().map(Line::key).toList());
        assertEquals(List.of("Years 2026", "UTC"), lines.get(1).args());
        assertEquals(List.of("2026", "2026-06-01", "2026-06-07"), lines.get(2).args(),
                "with no run on and none ahead, the last run frames the dates");
    }

    @Test
    void anEventOfSeveralRunsAYearSaysSoAndNamesEachRunByItsLabel() {
        CalendarFixtures.loadEvents(Map.of("two_fairs", CalendarFixtures.event("Two_Fairs", """
                { "Window": { "Rule": { "Type": "Monthly", "Day": 10, "Days": 7, "Months": [4, 9] } }, "FirstYear": 2026 }
                """)));
        List<Line> june = CalendarStatusLines.detail(service, "two_fairs", at("2026-06-01T12:00:00Z"));
        assertEquals(List.of("row.waiting", "status.window.several", "status.run", "status.first", "status.history"),
                june.stream().map(Line::key).toList());
        assertEquals(List.of("Monthly day 10 x7 in 04,09", "UTC"), june.get(1).args());
        assertEquals(List.of("2026#9", "2026-09-10", "2026-09-16"), june.get(2).args(), "the run it frames is the next");
        assertEquals(List.of("2026#4"), june.get(4).args(), "the spring run so far, by its month");
        assertEquals(new Line("row.live", List.of("two_fairs", "2026#9", "2026-09-16")),
                CalendarStatusLines.row(service, "two_fairs", at("2026-09-11T12:00:00Z")));
        assertEquals(List.of("2026#4,9"),
                CalendarStatusLines.detail(service, "two_fairs", at("2026-10-01T12:00:00Z")).get(4).args(),
                "a year of several runs names each run it has had");
    }

    @Test
    void theRunsSoFarNameEachYearsRunsByNumberWhereverTheNumbersSkip() {
        assertEquals("", CalendarStatusLines.runsSoFar(List.of()));
        assertEquals("2025, 2026", CalendarStatusLines.runsSoFar(List.of(run(2025, 1), run(2026, 1))),
                "a year whose one run is its first reads as the bare year, as a once-a-year event's always has");
        assertEquals("2026#2..4,7, 2027, 2028#3", CalendarStatusLines.runsSoFar(
                        List.of(run(2026, 2), run(2026, 3), run(2026, 4), run(2026, 7), run(2027, 1), run(2028, 3))),
                "runs one after another read as a stretch; a gap and a year's lone later run read as themselves");
        assertEquals("2026#1..2", CalendarStatusLines.runsSoFar(List.of(run(2026, 2), run(2026, 1))),
                "by number, whichever came round first");
    }

    /** Run {@code number} of {@code year} of one event, its days its number's hour of the year. */
    private static Occurrence run(int year, int number) {
        long start = at(year + "-01-01T00:00:00Z") + number * 3_600_000L;
        return new Occurrence("fair", year, number, start, start + 1_000L);
    }
}
