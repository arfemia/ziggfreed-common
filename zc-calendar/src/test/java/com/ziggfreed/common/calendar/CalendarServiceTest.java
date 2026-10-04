package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        CalendarForces.getInstance().force("Hallows_Eve", true);
        Occurrence forced = service.live("hallows_eve", june);
        assertNotNull(forced);
        assertEquals(2026, forced.year(), "what a forced run earns is filed under this year");
        assertEquals(List.of(2026), years(service.history("hallows_eve", june)), "the forced run is in the history once");
        assertEquals(Boolean.TRUE, service.forced("hallows_eve"));
    }

    @Test
    void forcingOffStopsARunningEventAndClearingHandsItBackToItsDates() {
        long during = at("2026-10-02T12:00:00Z");
        CalendarForces.getInstance().force("hallows_eve", false);
        assertNull(service.live("hallows_eve", during));
        assertTrue(service.isEnabled("hallows_eve"), "stopped is not switched off");
        CalendarForces.getInstance().clear("HALLOWS_EVE");
        assertNotNull(service.live("hallows_eve", during));
    }

    @Test
    void theOwnersSwitchBeatsAForce() {
        CalendarForces.getInstance().force("hallows_eve", true);
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertNull(service.live("hallows_eve", at("2026-06-01T00:00:00Z")));
    }

    @Test
    void theServiceAnswersAsTheOccurrenceSource() {
        OccurrenceSource source = service;
        assertTrue(source.isEnabled("Hallows_Eve"));
        assertEquals(2026, source.live("hallows_eve", at("2026-10-02T12:00:00Z")).year());
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
        CalendarForces.getInstance().force("Later_Fair", true);
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
