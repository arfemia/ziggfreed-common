package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;

/** The calendar file: every leaf, its defaults, what stops it running, and leaf-by-leaf inheritance. */
class CalendarEventAssetTest {

    @Test
    void everyLeafDecodes() {
        CalendarEventAsset event = CalendarFixtures.event("Test_Fair", """
                { "Enabled": true,
                  "Window": { "Start": "10-01", "End": "11-03" },
                  "FirstYear": 2026,
                  "Clock": "Europe/Paris",
                  "Presentation": { "TitleKey": "calendar.test_fair.name", "FlavorKey": "calendar.test_fair.flavor",
                                    "Icon": "Test_Icon" },
                  "Herald": { "Start": { "TitleKey": "calendar.test_fair.start",
                                         "SubtitleKey": "calendar.test_fair.start.sub", "Major": true },
                              "End": { "TitleKey": "calendar.test_fair.end" } } }
                """);
        assertEquals("test_fair", event.getId(), "ids fold to lower case");
        assertTrue(event.isEnabled());
        assertEquals("10-01..11-03", String.valueOf(event.annualWindow()));
        assertEquals(Integer.valueOf(2026), event.firstYear());
        assertEquals(ZoneId.of("Europe/Paris"), event.zone());
        assertEquals("calendar.test_fair.name", event.presentation().titleKey());
        assertEquals("calendar.test_fair.flavor", event.presentation().flavorKey());
        assertEquals("Test_Icon", event.presentation().icon());
        assertTrue(event.heraldStart().major());
        assertEquals("calendar.test_fair.start.sub", event.heraldStart().subtitleKey());
        assertFalse(event.heraldEnd().major(), "Major is false unless authored");
        assertNull(event.heraldEnd().subtitleKey());
        assertTrue(event.problems().isEmpty());
        assertTrue(event.canRun());
    }

    @Test
    void anUnauthoredFileIsOnRunsOnUtcAndHasNoBanner() {
        CalendarEventAsset event = CalendarFixtures.event("Bare", CalendarFixtures.HARVEST_MOON);
        assertTrue(event.isEnabled());
        assertEquals(ZoneOffset.UTC, event.zone());
        assertNull(event.presentation());
        assertNull(event.heraldStart());
        assertNull(event.heraldEnd());
    }

    @Test
    void aFileThatCannotRunSaysWhy() {
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_MISSING, CalendarEventAsset.PROBLEM_FIRST_YEAR_MISSING),
                CalendarFixtures.event("Empty", "{ }").problems());
        CalendarEventAsset unreadable = CalendarFixtures.event("Bad_Days",
                "{ \"Window\": { \"Start\": \"10-1\", \"End\": \"11-03\" }, \"FirstYear\": 2026 }");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE), unreadable.problems());
        assertFalse(unreadable.canRun());
    }

    @Test
    void anUnknownClockRunsOnUtcAndIsReported() {
        CalendarEventAsset event = CalendarFixtures.event("Far_Away",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"10-02\" }, \"FirstYear\": 2026,"
                        + " \"Clock\": \"Mars/Olympus_Mons\" }");
        assertEquals(ZoneOffset.UTC, event.zone());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_CLOCK_UNKNOWN), event.problems());
        assertTrue(event.canRun(), "an unknown clock costs the zone, never the event");
    }

    @Test
    void aChildKeepsEveryLeafItDoesNotAuthorOneDayOfTheWindowIncluded() {
        CalendarEventAsset parent = CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE);
        CalendarEventAsset child = CalendarFixtures.event("Hallows_Eve", "{ \"Window\": { \"End\": \"10-31\" } }", parent);
        assertEquals("10-01", child.windowStart());
        assertEquals("10-31", child.windowEnd());
        assertEquals(Integer.valueOf(2026), child.firstYear());
    }

    @Test
    void anIdAnotherSwitchAlreadyUsesNeverRuns() {
        for (String id : List.of("Calendar", "ALMANAC", "Hallows_Eve_Live", "harvest_live")) {
            CalendarEventAsset event = CalendarFixtures.event(id, CalendarFixtures.HARVEST_MOON);
            assertEquals(List.of(CalendarEventAsset.PROBLEM_ID_RESERVED), event.problems(), id);
            assertFalse(event.canRun(), id + " would take over a switch it does not own");
        }
        assertTrue(CalendarFixtures.event("Live_Music_Fair", CalendarFixtures.HARVEST_MOON).canRun(),
                "only an id ENDING in _Live is reserved");
        assertTrue(CalendarEventAsset.isReservedId(" almanac "));
        assertFalse(CalendarEventAsset.isReservedId(null));
    }
}
