package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/** Which run is going on, which have been, and what the owner's switches and an admin's force change. */
class CalendarServiceTest {

    private final CalendarService service =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance());

    @BeforeEach
    void loadTheDesignEvents() {
        CalendarFixtures.reset();
        CalendarFixtures.loadDesignEvents();
    }

    @AfterEach
    void reset() {
        CalendarFixtures.reset();
    }

    private static List<Integer> years(List<Occurrence> runs) {
        return runs.stream().map(Occurrence::year).toList();
    }

    /** An October fair whose file names {@code firstYear}, switched on or off in itself. */
    private static CalendarEventAsset fairFirstRunIn(String id, int firstYear, boolean enabled) {
        return CalendarFixtures.event(id, "{ \"Enabled\": " + enabled
                + ", \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": " + firstYear + " }");
    }

    @Test
    void hallowsEveRunsFromOctoberFirstThroughNovemberThird() {
        Occurrence run = service.live("Hallows_Eve", at("2026-10-02T12:00:00Z"));
        assertNotNull(run);
        assertEquals("hallows_eve", run.eventId());
        assertEquals(2026, run.year());
        assertEquals(at("2026-10-01T00:00:00Z"), run.startMs());
        assertEquals(at("2026-11-04T00:00:00Z"), run.endMs());
        assertNotNull(service.live("hallows_eve", at("2026-11-03T23:59:59Z")));
        assertNull(service.live("hallows_eve", at("2026-11-04T00:00:00Z")));
    }

    @Test
    void harvestMoonRunsInsideHallowsEve() {
        assertEquals(List.of("hallows_eve", "harvest_moon"),
                List.copyOf(service.liveAll(at("2026-10-30T22:00:00Z")).keySet()));
        assertEquals(List.of("hallows_eve"), List.copyOf(service.liveAll(at("2026-11-01T00:00:00Z")).keySet()));
    }

    @Test
    void nothingRunsBeforeTheFirstYear() {
        CalendarFixtures.loadEvents(Map.of("later_fair", CalendarFixtures.event("Later_Fair",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2027 }")));
        long october2026 = at("2026-10-02T12:00:00Z");
        assertNull(service.live("later_fair", october2026));
        assertTrue(service.history("later_fair", october2026).isEmpty());
        assertEquals(Long.valueOf(at("2027-10-01T00:00:00Z")), service.nextStartMs("later_fair", october2026));
    }

    @Test
    void historyListsEveryRunThatHasBegunOldestFirst() {
        CalendarFixtures.loadEvents(Map.of("old_fair", CalendarFixtures.event("Old_Fair",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2024 }")));
        assertEquals(List.of(2024, 2025, 2026), years(service.history("old_fair", at("2026-10-02T12:00:00Z"))));
        assertEquals(List.of(2024, 2025), years(service.history("old_fair", at("2026-06-01T00:00:00Z"))));
    }

    @Test
    void theOwnersSwitchMakesEveryEventAbsent() {
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        long now = at("2026-10-02T12:00:00Z");
        assertTrue(service.isLoaded("hallows_eve"), "the file is still there");
        assertFalse(service.isEnabled("hallows_eve"));
        assertNull(service.live("hallows_eve", now));
        assertTrue(service.history("hallows_eve", now).isEmpty(), "absent means no past either");
        assertNull(service.nextStartMs("hallows_eve", now));
        assertTrue(service.liveAll(now).isEmpty());
    }

    @Test
    void anEventsOwnSwitchMakesOnlyItAbsent() {
        CalendarFixtures.loadEvents(Map.of(
                "hallows_eve", CalendarFixtures.event("Hallows_Eve",
                        "{ \"Enabled\": false, \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2026 }"),
                "harvest_moon", CalendarFixtures.event("Harvest_Moon", CalendarFixtures.HARVEST_MOON)));
        long night = at("2026-10-30T22:00:00Z");
        assertNull(service.live("hallows_eve", night));
        assertNotNull(service.live("harvest_moon", night));
    }

    @Test
    void anEventThatCannotRunIsAbsent() {
        CalendarFixtures.loadEvents(Map.of("no_year", CalendarFixtures.event("No_Year",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" } }")));
        assertTrue(service.isLoaded("no_year"));
        assertFalse(service.isEnabled("no_year"));
        assertNull(service.live("no_year", at("2026-10-02T12:00:00Z")));
    }

    @Test
    void anUnknownEventIsNeitherLoadedNorLive() {
        assertFalse(service.isLoaded("no_such_event"));
        assertFalse(service.isEnabled("no_such_event"));
        assertNull(service.live("no_such_event", at("2026-10-02T12:00:00Z")));
    }

    @Test
    void forcingOnRunsThisYearsRunOutsideItsDates() {
        long june = at("2026-06-01T00:00:00Z");
        service.forceOn("Hallows_Eve", june);
        Occurrence forced = service.live("hallows_eve", june);
        assertNotNull(forced);
        assertEquals(2026, forced.year(), "what a forced run earns is filed under this year");
        assertEquals(List.of(2026), years(service.history("hallows_eve", june)), "the forced run is in the history once");
        assertEquals(Boolean.TRUE, service.forced("hallows_eve"));
    }

    @Test
    void forcingOffStopsARunningEventAndClearingHandsItBackToItsDates() {
        long during = at("2026-10-02T12:00:00Z");
        CalendarForces.getInstance().forceOff("hallows_eve");
        assertNull(service.live("hallows_eve", during));
        assertTrue(service.isEnabled("hallows_eve"), "stopped is not switched off");
        CalendarForces.getInstance().clear("HALLOWS_EVE");
        assertNotNull(service.live("hallows_eve", during));
    }

    @Test
    void theOwnersSwitchBeatsAForce() {
        service.forceOn("hallows_eve", at("2026-06-01T00:00:00Z"));
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertNull(service.live("hallows_eve", at("2026-06-01T00:00:00Z")));
    }

    @Test
    void theServiceAnswersAsTheOccurrenceSource() {
        OccurrenceSource source = service;
        assertTrue(source.isEnabled("Hallows_Eve"));
        assertEquals(2026, source.live("hallows_eve", at("2026-10-02T12:00:00Z")).year());
        assertEquals(2027, source.next("Hallows_Eve", at("2026-10-02T12:00:00Z")).year());
        assertEquals(ZoneOffset.UTC, source.zone("hallows_eve"));
    }

    /** One run of Hallow's Eve as its dates give it: October 1st through November 3rd of {@code year}, in UTC. */
    private static Occurrence hallowsEveIn(int year) {
        return new Occurrence("hallows_eve", year, at(year + "-10-01T00:00:00Z"), at(year + "-11-04T00:00:00Z"));
    }

    @Test
    void theNextRunIsTheFirstToStartAfterNowAndNeverTheRunGoingOn() {
        assertEquals(hallowsEveIn(2026), service.next("Hallows_Eve", at("2026-06-01T00:00:00Z")),
                "before its dates, this year's run");
        long during = at("2026-10-02T12:00:00Z");
        assertNotNull(service.live("hallows_eve", during));
        assertEquals(hallowsEveIn(2027), service.next("hallows_eve", during),
                "while this year's run goes on, the next is next year's");
        assertEquals(hallowsEveIn(2027), service.next("hallows_eve", at("2026-10-01T00:00:00Z")),
                "a run's first instant is already inside it, so it is no longer to come");
        assertEquals(hallowsEveIn(2027), service.next("hallows_eve", at("2026-12-31T23:59:59Z")),
                "after this year's run, across the new year, next October's");
    }

    @Test
    void onTheLastDayTheNextRunIsNextYearsAndARunEndsAtTheMidnightAfterItsLastDay() {
        long lastDay = at("2026-11-03T23:59:59Z");
        assertNotNull(service.live("hallows_eve", lastDay), "the last day is in the run");
        assertEquals(hallowsEveIn(2027), service.next("hallows_eve", lastDay),
                "its end is the first instant of November 4th, so November 3rd counts whole");
        assertEquals(hallowsEveIn(2027), service.next("hallows_eve", at("2026-11-04T00:00:00Z")),
                "the moment it is over, the same run is next");
    }

    @Test
    void aWindowCrossingTheNewYearBelongsToTheYearItStartsIn() {
        CalendarFixtures.loadEvents(Map.of("winter_fair", CalendarFixtures.event("Winter_Fair",
                "{ \"Window\": { \"Start\": \"12-20\", \"End\": \"01-05\" }, \"FirstYear\": 2024 }")));
        assertEquals(new Occurrence("winter_fair", 2026, at("2026-12-20T00:00:00Z"), at("2027-01-06T00:00:00Z")),
                service.next("winter_fair", at("2026-12-01T00:00:00Z")), "it ends in the next year's January");
        long earlyJanuary = at("2027-01-03T12:00:00Z");
        assertEquals(2026, service.live("winter_fair", earlyJanuary).year(), "early January is December's run");
        Occurrence thisDecember =
                new Occurrence("winter_fair", 2027, at("2027-12-20T00:00:00Z"), at("2028-01-06T00:00:00Z"));
        assertEquals(thisDecember, service.next("winter_fair", earlyJanuary),
                "the run going on began last December, so the next begins this December");
        assertEquals(thisDecember, service.next("winter_fair", at("2027-01-06T00:00:00Z")),
                "and once it is over, still this December's");
    }

    @Test
    void theNextRunIsNeverBeforeTheFirstYear() {
        CalendarFixtures.loadEvents(Map.of("later_fair", CalendarFixtures.event("Later_Fair",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2028 }")));
        long october2026 = at("2026-10-02T12:00:00Z");
        assertNull(service.live("later_fair", october2026), "inside its dates, two years early");
        assertEquals(new Occurrence("later_fair", 2028, at("2028-10-01T00:00:00Z"), at("2028-11-04T00:00:00Z")),
                service.next("later_fair", october2026), "its first run is still the next");
    }

    @Test
    void forcedOffTheNextRunIsWhenItsDatesNextComeRound() {
        long during = at("2026-10-02T12:00:00Z");
        CalendarForces.getInstance().forceOff("Hallows_Eve");
        assertNull(service.live("hallows_eve", during));
        assertEquals(hallowsEveIn(2027), service.next("hallows_eve", during),
                "a stopped run returns next October, since this October's start has passed");
        assertEquals(hallowsEveIn(2026), service.next("hallows_eve", at("2026-06-01T00:00:00Z")),
                "before its dates, stopped or not, the next is this year's by its dates");
    }

    @Test
    void forcedOnAheadOfItsDatesTheRunGoingOnIsNeverTheNext() {
        long june = at("2026-06-01T00:00:00Z");
        service.forceOn("hallows_eve", june);
        assertEquals(2026, service.live("hallows_eve", june).year());
        assertEquals(hallowsEveIn(2027), service.next("hallows_eve", june),
                "this year's run is going on already, forced, so the next is next year's");
        CalendarForces.getInstance().clear("hallows_eve");
        assertEquals(hallowsEveIn(2026), service.next("hallows_eve", june), "cleared, this year's run is ahead again");

        CalendarFixtures.loadEvents(Map.of("later_fair", CalendarFixtures.event("Later_Fair",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2028 }")));
        service.forceOn("later_fair", june);
        assertEquals(2028, service.live("later_fair", june).year(), "a forced run is filed under the first year");
        assertEquals(2029, service.next("later_fair", june).year(), "so the next run is the year after it");
    }

    @Test
    void anAbsentEventHasNoNextRun() {
        long june = at("2026-06-01T00:00:00Z");
        CalendarFixtures.loadEvents(Map.of(
                "hallows_eve", CalendarFixtures.event("Hallows_Eve", "{ \"Enabled\": false,"
                        + " \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2026 }"),
                "harvest_moon", CalendarFixtures.event("Harvest_Moon", CalendarFixtures.HARVEST_MOON),
                "no_year", CalendarFixtures.event("No_Year",
                        "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" } }")));
        assertNull(service.next("hallows_eve", june), "switched off in itself");
        assertNull(service.next("no_year", june), "it cannot run");
        assertNull(service.next("no_such_event", june), "not loaded");
        assertNotNull(service.next("harvest_moon", june));
        service.forceOn("harvest_moon", june);
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertNull(service.next("harvest_moon", june), "the owner's switch beats the dates and a force");
    }

    @Test
    void theDaysAreCountedInTheEventsOwnClock() {
        CalendarFixtures.loadEvents(Map.of(
                "hallows_eve", CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE),
                "tokyo_fair", CalendarFixtures.event("Tokyo_Fair",
                        "{ \"Window\": { \"Start\": \"06-01\", \"End\": \"06-07\" }, \"FirstYear\": 2026,"
                                + " \"Clock\": \"Asia/Tokyo\" }"),
                "mars_fair", CalendarFixtures.event("Mars_Fair",
                        "{ \"Window\": { \"Start\": \"06-01\", \"End\": \"06-07\" }, \"FirstYear\": 2026,"
                                + " \"Clock\": \"Mars/Olympus\" }")));
        assertEquals(ZoneId.of("Asia/Tokyo"), service.zone("Tokyo_Fair"));
        assertEquals(ZoneOffset.UTC, service.zone("hallows_eve"), "a file with no Clock counts in UTC");
        assertEquals(ZoneOffset.UTC, service.zone("mars_fair"), "a Clock java.time does not know runs on UTC");
        assertEquals(ZoneOffset.UTC, service.zone("no_such_event"));

        long lateMayInTokyo = at("2026-05-31T14:00:00Z");
        assertEquals(new Occurrence("tokyo_fair", 2026, at("2026-05-31T15:00:00Z"), at("2026-06-07T15:00:00Z")),
                service.next("tokyo_fair", lateMayInTokyo),
                "June 1st begins at Tokyo's midnight, 15:00 the day before in UTC, and June 7th counts whole there");
        long juneFirstInTokyo = at("2026-05-31T16:00:00Z");
        assertNotNull(service.live("tokyo_fair", juneFirstInTokyo), "still May 31st in UTC, already June 1st in Tokyo");
        assertEquals(2027, service.next("tokyo_fair", juneFirstInTokyo).year());
    }

    @Test
    void theNextRunsYearIsTheYearInTheEventsOwnClock() {
        CalendarFixtures.loadEvents(Map.of("new_year_fair", CalendarFixtures.event("New_Year_Fair",
                "{ \"Window\": { \"Start\": \"01-01\", \"End\": \"01-03\" }, \"FirstYear\": 2026,"
                        + " \"Clock\": \"Asia/Tokyo\" }")));
        assertEquals(new Occurrence("new_year_fair", 2027, at("2026-12-31T15:00:00Z"), at("2027-01-03T15:00:00Z")),
                service.next("new_year_fair", at("2026-12-31T14:00:00Z")),
                "an hour before Tokyo's new year it starts within the hour, in 2027 though UTC still reads 2026");
        assertEquals(2028, service.next("new_year_fair", at("2026-12-31T15:00:00Z")).year(),
                "at Tokyo's midnight the 2027 run is going on, so the next is 2028's");
    }

    @Test
    void theClockOutlivesTheSwitches() {
        long may = at("2026-05-01T00:00:00Z");
        for (boolean ownSwitch : List.of(true, false)) {
            CalendarFixtures.loadEvents(Map.of("tokyo_fair", CalendarFixtures.event("Tokyo_Fair",
                    "{ \"Enabled\": " + ownSwitch + ", \"Window\": { \"Start\": \"06-01\", \"End\": \"06-07\" },"
                            + " \"FirstYear\": 2026, \"Clock\": \"Asia/Tokyo\" }")));
            for (boolean ownersSwitch : List.of(true, false)) {
                CalendarEventConfig.getInstance().setGlobalEnabled(ownersSwitch);
                String switches = " (its own switch " + ownSwitch + ", the owner's " + ownersSwitch + ")";
                assertEquals(ownSwitch && ownersSwitch, service.next("tokyo_fair", may) != null,
                        "a next run only while switched on" + switches);
                assertEquals(ZoneId.of("Asia/Tokyo"), service.zone("tokyo_fair"),
                        "loaded, so its clock is known, like its years" + switches);
            }
        }
    }

    @Test
    void theYearsStayKnownWhileTheEventIsSwitchedOff() {
        long october = at("2026-10-02T12:00:00Z");
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertFalse(service.isEnabled("hallows_eve"), "absent to every run question");
        assertEquals(Integer.valueOf(2026), service.firstYear("Hallows_Eve"),
                "what a player earned in a past run keeps its years");
        assertEquals(Integer.valueOf(2026), service.currentYear("hallows_eve", october));
        CalendarEventConfig.getInstance().setGlobalEnabled(true);
        CalendarFixtures.loadEvents(Map.of("hallows_eve", CalendarFixtures.event("Hallows_Eve",
                "{ \"Enabled\": false, \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2026 }")));
        assertNull(service.live("hallows_eve", october));
        assertEquals(Integer.valueOf(2026), service.firstYear("hallows_eve"), "its own switch, the same");
        assertEquals(Integer.valueOf(2026), service.currentYear("hallows_eve", october));
    }

    @Test
    void theCurrentYearIsTheYearTheRunGoingOnBeganInElseTheCalendarYearInItsClock() {
        CalendarFixtures.loadEvents(Map.of(
                "winter_fair", CalendarFixtures.event("Winter_Fair",
                        "{ \"Window\": { \"Start\": \"12-20\", \"End\": \"01-05\" }, \"FirstYear\": 2024 }"),
                "tokyo_fair", CalendarFixtures.event("Tokyo_Fair",
                        "{ \"Window\": { \"Start\": \"06-01\", \"End\": \"06-07\" }, \"FirstYear\": 2026,"
                                + " \"Clock\": \"Asia/Tokyo\" }")));
        assertEquals(Integer.valueOf(2026), service.currentYear("winter_fair", at("2027-01-03T12:00:00Z")),
                "early January is still the run that began in December");
        assertEquals(Integer.valueOf(2027), service.currentYear("winter_fair", at("2027-02-01T00:00:00Z")),
                "between runs it is the calendar year");
        assertEquals(Integer.valueOf(2027), service.currentYear("tokyo_fair", at("2026-12-31T20:00:00Z")),
                "the new year has already come in the event's own clock");
    }

    @Test
    void aRunForcedOnIsTheCurrentYearSoItsCopyExists() {
        CalendarFixtures.loadEvents(Map.of("later_fair", CalendarFixtures.event("Later_Fair",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2028 }")));
        long june = at("2026-06-01T00:00:00Z");
        assertEquals(Integer.valueOf(2026), service.currentYear("later_fair", june),
                "before the first run it is the calendar year, earlier than the first year");
        service.forceOn("Later_Fair", june);
        Occurrence forced = service.live("later_fair", june);
        assertNotNull(forced);
        assertEquals(2028, forced.year(), "a forced run is never filed before the first year");
        assertEquals(Integer.valueOf(forced.year()), service.currentYear("later_fair", june),
                "whenever a run is live, the current year is its year");
    }

    @Test
    void theYearsAnswerForEveryLoadedEventAndNothingElse() {
        CalendarFixtures.loadEvents(Map.of("no_year", CalendarFixtures.event("No_Year",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" } }")));
        long october = at("2026-10-02T12:00:00Z");
        assertNull(service.firstYear("no_year"), "its file states none");
        assertEquals(Integer.valueOf(2026), service.currentYear("no_year", october),
                "loaded, so it is in a year even though it never runs");
        assertNull(service.firstYear("no_such_event"));
        assertNull(service.currentYear("no_such_event", october));
    }

    @Test
    void aFirstYearBefore1970OrAfter9999IsNoFirstYearSwitchedOnOrOff() {
        for (boolean ownSwitch : List.of(true, false)) {
            CalendarFixtures.loadEvents(Map.of(
                    "too_early", fairFirstRunIn("Too_Early", 1969, ownSwitch),
                    "earliest", fairFirstRunIn("Earliest", 1970, ownSwitch),
                    "latest", fairFirstRunIn("Latest", 9999, ownSwitch),
                    "too_late", fairFirstRunIn("Too_Late", 10000, ownSwitch)));
            assertTrue(service.isLoaded("too_early") && service.isLoaded("too_late"),
                    "both files are loaded; only their FirstYear is out of bounds");
            for (boolean ownersSwitch : List.of(true, false)) {
                CalendarEventConfig.getInstance().setGlobalEnabled(ownersSwitch);
                String switches = " (its own switch " + ownSwitch + ", the owner's " + ownersSwitch + ")";
                assertNull(service.firstYear("Too_Early"),
                        "1969 is before the bound, so a copy per year from it has no year to start at" + switches);
                assertNull(service.firstYear("too_late"), "10000 is after the bound" + switches);
                assertEquals(Integer.valueOf(1970), service.firstYear("earliest"), "the bound's first year" + switches);
                assertEquals(Integer.valueOf(9999), service.firstYear("latest"), "the bound's last year" + switches);
            }
        }
    }
}
