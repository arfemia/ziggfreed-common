package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.calendar.attendance.CalendarAttendanceComponent;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.calendar.tick.CalendarTransitions;
import com.ziggfreed.common.occurrence.Occurrence;

/**
 * The calendar's answers for a Window that is more than one pair of month-days: a run crossing the new
 * year, a Rule around Easter or a weekday, days for particular years, a window of per-year days that
 * runs out, and a force, every one of them keyed (event, year).
 */
class CalendarWindowRuleServiceTest {

    private static final String EASTER_HUNT = """
            { "Window": { "Rule": { "Type": "Easter", "Before": 10, "After": 7 } }, "FirstYear": 2027 }
            """;

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

    private static List<Integer> years(List<Occurrence> runs) {
        return runs.stream().map(Occurrence::year).toList();
    }

    @Test
    void aRunCrossingTheNewYearBelongsToTheYearItStartsEverywhereTheCalendarAnswers() {
        load("Winter_Fest", "{ \"Window\": { \"Start\": \"12-15\", \"End\": \"01-06\" }, \"FirstYear\": 2026 }");
        long december = at("2026-12-20T12:00:00Z");
        long january = at("2027-01-03T12:00:00Z");

        Occurrence before = service.live("winter_fest", december);
        Occurrence after = service.live("winter_fest", january);
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(2026, after.year(), "early January is still the run that began in December");
        assertEquals(before, after, "one run, the same (event, year), on both sides of midnight");
        assertEquals(Integer.valueOf(2026), service.currentYear("winter_fest", january));
        assertEquals(Double.valueOf(2026), CalendarFactors.year(service, "Winter_Fest", january),
                "the calendar_year reading is the December's");
        assertEquals(List.of(2026), years(service.history("winter_fest", january)),
                "no 2027 run has begun in January");
        Occurrence next = service.next("winter_fest", january);
        assertNotNull(next);
        assertEquals(2027, next.year());
        assertEquals(at("2027-12-15T00:00:00Z"), next.startMs());

        long newYear = at("2027-01-01T00:01:00Z");
        CalendarTick tick = CalendarTransitions.diff(service.liveAll(at("2026-12-31T23:59:00Z")),
                service.liveAll(newYear), service::isEnabled, newYear, false);
        assertTrue(tick.started().isEmpty(), "the new year starts no run");
        assertTrue(tick.ended().isEmpty(), "and ends none");

        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        assertTrue(record.markAttended(before.eventId(), before.year()), "December's credit is the run's");
        assertFalse(record.markAttended(after.eventId(), after.year()),
                "January's is the same run's, so attendance is credited once");
    }

    @Test
    void aYearsEntryCrossingTheNewYearBelongsToItsOwnYear() {
        load("Winter_Fest", """
                { "Window": { "Start": "12-15", "End": "01-06",
                              "Years": { "2027": { "Start": "12-20", "End": "01-02" } } }, "FirstYear": 2026 }
                """);
        Occurrence run = service.live("winter_fest", at("2028-01-01T12:00:00Z"));
        assertNotNull(run);
        assertEquals(2027, run.year());
        assertEquals(at("2027-12-20T00:00:00Z"), run.startMs());
        assertEquals(at("2028-01-03T00:00:00Z"), run.endMs());
        assertNull(service.live("winter_fest", at("2028-01-04T12:00:00Z")),
                "the 2027 run ends on its own last day, not the every-year one");
    }

    @Test
    void anEasterRuleRunsAroundEachYearsEasterSunday() {
        load("Egg_Hunt", EASTER_HUNT);
        Occurrence run = service.live("egg_hunt", at("2027-03-28T12:00:00Z"));
        assertNotNull(run);
        assertEquals(2027, run.year());
        assertEquals(at("2027-03-18T00:00:00Z"), run.startMs(), "ten days before Easter Sunday, March 28th");
        assertEquals(at("2027-04-05T00:00:00Z"), run.endMs(), "through the seventh day after it");
        Occurrence next = service.next("egg_hunt", at("2027-06-01T00:00:00Z"));
        assertNotNull(next);
        assertEquals(2028, next.year());
        assertEquals(at("2028-04-06T00:00:00Z"), next.startMs(), "Easter 2028 is April 16th");
    }

    @Test
    void aWeekdayRuleRunsAroundTheNthWeekdayOfItsMonth() {
        load("Harvest_Fair", """
                { "Window": { "Rule": { "Type": "Weekday", "Month": 11, "Weekday": "Thursday", "Nth": 4,
                                        "Before": 6, "After": 5 } }, "FirstYear": 2026 }
                """);
        Occurrence run = service.live("harvest_fair", at("2026-11-26T12:00:00Z"));
        assertNotNull(run);
        assertEquals(at("2026-11-20T00:00:00Z"), run.startMs());
        assertEquals(at("2026-12-02T00:00:00Z"), run.endMs());
        assertEquals(at("2027-11-19T00:00:00Z"), service.next("harvest_fair", at("2026-12-10T00:00:00Z")).startMs(),
                "the fourth Thursday of November 2027 is the 25th");
    }

    @Test
    void aYearsEntryOverridesTheRuleForItsYearOnly() {
        load("Egg_Hunt", """
                { "Window": { "Rule": { "Type": "Easter", "Before": 10, "After": 7 },
                              "Years": { "2028": { "Start": "04-01", "End": "04-20" } } }, "FirstYear": 2027 }
                """);
        Occurrence run = service.live("egg_hunt", at("2028-04-02T12:00:00Z"));
        assertNotNull(run, "the Rule's 2028 run would not start until April 6th");
        assertEquals(at("2028-04-01T00:00:00Z"), run.startMs());
        assertEquals(at("2028-04-21T00:00:00Z"), run.endMs());
        assertNull(service.live("egg_hunt", at("2028-04-22T12:00:00Z")),
                "the Rule's run would still be on; the entry's has ended");
        assertEquals(at("2029-03-22T00:00:00Z"), service.next("egg_hunt", at("2028-06-01T00:00:00Z")).startMs(),
                "the year after, the Rule again: Easter 2029 is April 1st");
    }

    @Test
    void aWindowOfPerYearDaysThatRunsOutAnswersNoNextRunWithoutLooping() {
        load("Fair", """
                { "Window": { "Years": { "2026": { "Start": "06-01", "End": "06-07" },
                                         "2027": { "Start": "06-03", "End": "06-09" } } }, "FirstYear": 2026 }
                """);
        long after = at("2028-01-01T00:00:00Z");
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertNull(service.next("fair", after));
            assertNull(service.nextStartMs("fair", after));
        }, "a table that has run out answers at once, never by walking year after year");
        assertEquals(List.of(2026, 2027), years(service.history("fair", after)));
        assertNull(service.live("fair", at("2028-06-05T00:00:00Z")), "a year the table does not list has no run");
        assertTrue(service.isEnabled("fair"), "it is still switched on; it has simply run out of days");

        CalendarForces.getInstance().force("fair", true);
        assertNull(service.live("fair", at("2028-06-05T00:00:00Z")),
                "a force has no days to run in a year the table does not date");
    }

    @Test
    void aForcedRunKeepsItsRulesDaysForTheForcedYear() {
        load("Egg_Hunt", EASTER_HUNT);
        long july = at("2027-07-01T00:00:00Z");
        CalendarForces.getInstance().force("Egg_Hunt", true);
        Occurrence forced = service.live("egg_hunt", july);
        assertNotNull(forced);
        assertEquals(2027, forced.year(), "what a forced run earns is filed under this year");
        assertEquals(at("2027-03-18T00:00:00Z"), forced.startMs(), "with this year's Easter days");
        assertEquals(at("2027-04-05T00:00:00Z"), forced.endMs());
        assertEquals(2028, service.next("egg_hunt", july).year(), "the run after the forced one is next year's");
    }

    // Review Focus 2: a forced or re-dated run keeps its (event, year), so attendance is credited once.
    @Test
    void aForcedRunForcedAgainOrReDatedKeepsItsYearAndCreditsAttendanceOnce() {
        load("Harvest_Feast", "{ \"Window\": { \"Start\": \"11-20\", \"End\": \"12-01\" }, \"FirstYear\": 2026 }");
        long october = at("2026-10-20T12:00:00Z");
        CalendarForces forces = CalendarForces.getInstance();
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();

        forces.force("Harvest_Feast", true);
        Occurrence forced = service.live("harvest_feast", october);
        assertNotNull(forced, "forced on a month before its dates, it runs");
        assertEquals(2026, forced.year(), "the run of the current year");
        assertEquals(Integer.valueOf(2026), service.currentYear("harvest_feast", october),
                "the year a yearly copy is minted for is the forced run's");
        assertEquals(List.of(2026), years(service.history("harvest_feast", october)), "one 2026 run, listed once");
        assertTrue(record.markAttended(forced.eventId(), forced.year()), "the forced run's attendance is credited");

        forces.force("Harvest_Feast", false);
        assertNull(service.live("harvest_feast", october), "forced off, nothing runs");
        forces.force("Harvest_Feast", true);
        Occurrence again = service.live("harvest_feast", october);
        assertNotNull(again);
        assertEquals(forced.eventId(), again.eventId());
        assertEquals(forced.year(), again.year(), "forced on again, it is the same (event, year)");
        assertFalse(record.markAttended(again.eventId(), again.year()), "so attendance is not credited twice");

        // The owner moves the window mid-run to include today, through the owner layer calendar.json fills.
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        config.mergeOwnerLayer(Map.of("harvest_feast", CalendarFixtures.event("Harvest_Feast",
                "{ \"Window\": { \"Start\": \"10-15\", \"End\": \"10-30\" } }", config.resolve("harvest_feast"))));
        Occurrence redated = service.live("harvest_feast", october);
        assertNotNull(redated);
        assertEquals(forced.year(), redated.year(), "re-dated while forced, the run keeps its year");
        assertEquals(at("2026-10-15T00:00:00Z"), redated.startMs(), "on the owner's days");
        forces.clear("Harvest_Feast");
        Occurrence byDates = service.live("harvest_feast", october);
        assertNotNull(byDates, "the owner's dates alone now hold the run");
        assertEquals(forced.year(), byDates.year());
        assertFalse(record.markAttended(byDates.eventId(), byDates.year()), "and attendance is still credited once");
        assertEquals(List.of(2026), record.yearsAttended("Harvest_Feast"));
    }
}
