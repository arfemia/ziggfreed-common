package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.RunDays;

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

    @Test
    void anIdThePlayersAttendanceRecordCannotHoldNeverRuns() {
        for (String id : List.of("Fair|Night", "Fair@Home")) {
            CalendarEventAsset event = CalendarFixtures.event(id, CalendarFixtures.HARVEST_MOON);
            assertEquals(List.of(CalendarEventAsset.PROBLEM_ID_UNSAVABLE), event.problems(), id);
            assertFalse(event.canRun(), id + " could never be credited, so it never runs");
            assertTrue(CalendarEventAsset.isReservedId(id), id + " declares no feature either");
        }
        assertFalse(CalendarEventConfig.sentence(CalendarEventAsset.PROBLEM_ID_UNSAVABLE).startsWith("has a problem"),
                "the log says why in a sentence of its own");
    }

    @Test
    void aFirstYearBefore1970OrAfter9999NeverRuns() {
        for (int year : List.of(1969, 10000)) {
            CalendarEventAsset event = firstRunIn(year);
            assertEquals(List.of(CalendarEventAsset.PROBLEM_FIRST_YEAR_OUT_OF_RANGE), event.problems(),
                    "FirstYear " + year);
            assertFalse(event.canRun(), "FirstYear " + year + " is outside 1970 to 9999, so the event never runs");
        }
        for (int year : List.of(1970, 9999)) {
            CalendarEventAsset event = firstRunIn(year);
            assertTrue(event.problems().isEmpty(), "FirstYear " + year + " is inside 1970 to 9999");
            assertTrue(event.canRun(), "FirstYear " + year + " runs");
        }
    }

    /** A fair whose first run is in {@code year}, with nothing else wrong with it. */
    private static CalendarEventAsset firstRunIn(int year) {
        return CalendarFixtures.event("Year_Fair",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": " + year + " }");
    }

    private static CalendarEventAsset rule(String json) {
        return CalendarFixtures.event("Rule_Fair", "{ \"Window\": { \"Rule\": " + json + " }, \"FirstYear\": 2026 }");
    }

    @Test
    void aRuleWinsOverStartAndEndAndSaysTheyAreUnused() {
        CalendarEventAsset event = CalendarFixtures.event("Egg_Hunt", """
                { "Window": { "Start": "04-01", "End": "04-10",
                              "Rule": { "Type": "Easter", "Before": 10, "After": 7 } }, "FirstYear": 2027 }
                """);
        assertTrue(event.problems().isEmpty(), "a Rule beside Start and End is no problem");
        assertEquals(List.of(CalendarEventAsset.NOTE_START_END_IGNORED), event.notes(),
                "but the author is told they do nothing");
        assertNotNull(event.annualWindow());
        assertEquals(new RunDays(LocalDate.of(2027, 3, 18), LocalDate.of(2027, 4, 4)), event.annualWindow().days(2027));
        assertTrue(event.annualWindow().moves());
        assertEquals("Easter -10..+7", String.valueOf(event.annualWindow()));
    }

    @Test
    void aRuleTheCalendarCannotUseSaysWhy() {
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Weekday\", \"Month\": 11, \"Weekday\": \"Thursdy\", \"Nth\": 4 }").problems());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Weekday\", \"Month\": 13, \"Weekday\": \"Thursday\", \"Nth\": 4 }").problems());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID),
                rule("{ \"Type\": \"Weekday\", \"Month\": 11, \"Weekday\": \"Thursday\", \"Nth\": 6 }").problems());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID),
                rule("{ \"Type\": \"Easter\", \"Before\": 81 }").problems(),
                "a run that could start in the year before");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID),
                rule("{ \"Type\": \"Easter\", \"After\": 366 }").problems(), "a run of 367 days");
        assertFalse(rule("{ \"Type\": \"Easter\", \"Before\": 81 }").canRun());
        assertTrue(rule("{ \"Type\": \"Easter\", \"Before\": 80, \"After\": 7 }").canRun());
        for (String code : List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID,
                CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED, CalendarEventAsset.NOTE_START_END_IGNORED)) {
            assertFalse(CalendarEventConfig.sentence(code).startsWith("has a problem"),
                    code + " is told in a sentence of its own");
        }
    }

    @Test
    void aYearsEntryThatIsNotAYearFromTheFirstOnIsSetAsideAndTheEventStillRuns() {
        CalendarEventAsset event = CalendarFixtures.event("Fair", """
                { "Window": { "Start": "06-01", "End": "06-07",
                              "Years": { "31": { "Start": "06-02", "End": "06-08" },
                                         "2025": { "Start": "06-02", "End": "06-08" },
                                         "2027": { "Start": "06-31", "End": "07-02" },
                                         "2028": { "Start": "06-10", "End": "06-12" } } },
                  "FirstYear": 2026 }
                """);
        assertEquals(List.of(CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED), event.problems());
        assertTrue(event.canRun(), "a bad entry costs that entry, never the event");
        assertEquals(LocalDate.of(2027, 6, 1), event.annualWindow().days(2027).first(),
                "an unreadable entry leaves its year to Start and End");
        assertEquals(LocalDate.of(2028, 6, 10), event.annualWindow().days(2028).first());
    }

    @Test
    void aChildChangesOneLeafOfItsParentsRule() {
        CalendarEventAsset parent = CalendarFixtures.event("Egg_Hunt", """
                { "Window": { "Rule": { "Type": "Easter", "Before": 10, "After": 7 } }, "FirstYear": 2027 }
                """);
        CalendarEventAsset child = CalendarFixtures.event("Egg_Hunt",
                "{ \"Window\": { \"Rule\": { \"After\": 9 } } }", parent);
        assertEquals(new RunDays(LocalDate.of(2027, 3, 18), LocalDate.of(2027, 4, 6)), child.annualWindow().days(2027),
                "the child keeps the parent's Type and Before and moves only After");
    }

    @Test
    void aRuleNamesItsType() {
        assertThrows(RuntimeException.class, () -> CalendarFixtures.event("No_Type",
                        "{ \"Window\": { \"Rule\": { \"Before\": 10 } }, \"FirstYear\": 2027 }"),
                "a Rule with no Type and nothing to inherit one from cannot be read");
    }

    // M308-M310 worked example: Orbis's anniversary, a one-day event every January 13th. No new schema.
    @Test
    void theAnniversaryIsAOneDayEventEveryJanuary13th() {
        CalendarEventAsset anniversary = CalendarFixtures.event("Orbis_Anniversary", """
                {
                  "Window": { "Start": "01-13", "End": "01-13" },
                  "FirstYear": 2027,
                  "Presentation": { "TitleKey": "calendar.Orbis_Anniversary.name",
                                    "FlavorKey": "calendar.Orbis_Anniversary.flavor" },
                  "Herald": { "Start": { "TitleKey": "calendar.Orbis_Anniversary.herald.start", "Major": true } }
                }
                """);
        assertTrue(anniversary.problems().isEmpty(), anniversary.problems().toString());
        assertTrue(anniversary.canRun());
        assertEquals(List.of(new RunDays(LocalDate.of(2027, 1, 13), LocalDate.of(2027, 1, 13))),
                anniversary.annualWindow().runs(2027), "one run a year, one day long");
        assertEquals(1L, anniversary.annualWindow().runs(2028).get(0).length());
        assertFalse(anniversary.annualWindow().several());
        assertFalse(anniversary.annualWindow().moves(), "the same day every year");
        assertTrue(anniversary.annualWindow().runs(2026).isEmpty(), "nothing before its first year");
    }

    // M308-M310 worked example: the traveling fair, the first Sunday of every month for seven days.
    @Test
    void theTravelingFairRunsTheFirstSundayOfEveryMonthForSevenDays() {
        CalendarEventAsset fair = CalendarFixtures.event("Traveling_Fair", """
                {
                  "Window": { "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 } },
                  "FirstYear": 2026,
                  "Presentation": { "TitleKey": "calendar.Traveling_Fair.name" },
                  "Herald": { "Start": { "TitleKey": "calendar.Traveling_Fair.herald.start" },
                              "End": { "TitleKey": "calendar.Traveling_Fair.herald.end" } }
                }
                """);
        assertTrue(fair.problems().isEmpty(), fair.problems().toString());
        List<RunDays> runs = fair.annualWindow().runs(2026);
        assertEquals(12, runs.size(), "one run a month");
        assertEquals(new RunDays(LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 10)), runs.get(0));
        assertEquals(new RunDays(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10)), runs.get(9));
        assertEquals(new RunDays(LocalDate.of(2027, 10, 3), LocalDate.of(2027, 10, 9)),
                fair.annualWindow().runs(2027).get(9), "the first Sunday moves with the year");
        assertTrue(fair.annualWindow().several());
        assertEquals("Monthly SUNDAY #1 x7", String.valueOf(fair.annualWindow()));
    }

    // The fishing contest: every Sunday from 14:00 for two hours, on the event's clock.
    @Test
    void theFishingContestRunsEverySundayFromTwoForTwoHours() {
        CalendarEventAsset contest = CalendarFixtures.event("Fishing_Contest", """
                { "Window": { "Rule": { "Type": "Weekly", "Weekday": "Sunday", "At": "14:00", "Length": "PT2H" } },
                  "FirstYear": 2026, "Clock": "UTC" }
                """);
        assertTrue(contest.problems().isEmpty(), contest.problems().toString());
        assertEquals(52, contest.annualWindow().runs(2026).size());
        assertEquals(new RunDays(LocalDateTime.of(2026, 10, 11, 14, 0), LocalDateTime.of(2026, 10, 11, 16, 0)),
                contest.annualWindow().runs(2026).get(40));
    }

    @Test
    void aRepeatingRuleTheCalendarCannotUseSaysWhy() {
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Monthly\", \"Days\": 3 }").problems(), "neither a Day nor a Weekday");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Monthly\", \"Weekday\": \"Sundy\", \"Nth\": 1 }").problems());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Monthly\", \"Day\": 1, \"Months\": [13] }").problems());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Weekly\" }").problems(), "a week needs its weekday");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Weekly\", \"Weekday\": \"Sunday\", \"At\": \"2pm\" }").problems());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Weekly\", \"Weekday\": \"Sunday\", \"Length\": \"2 hours\" }").problems(),
                "a typo costs the event a sentence in the log, never the whole file");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Weekly\", \"Weekday\": \"Monday\", \"Every\": 2 }").problems(),
                "skipping weeks needs an Anchor to count from");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID),
                rule("{ \"Type\": \"Monthly\", \"Day\": 1, \"Days\": 29 }").problems(), "29 days meet the next run");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID),
                rule("{ \"Type\": \"Weekly\", \"Weekday\": \"Friday\", \"Days\": 8 }").problems());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID),
                rule("{ \"Type\": \"Monthly\", \"Day\": 1, \"Months\": [] }").problems(), "no month to run in");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID),
                rule("{ \"Type\": \"Easter\", \"After\": 350 }").problems(), "351 days could meet next Easter's run");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID), CalendarFixtures.event("Leap",
                "{ \"Window\": { \"Start\": \"02-29\", \"End\": \"02-28\" }, \"FirstYear\": 2026 }").problems());
        assertTrue(rule("{ \"Type\": \"Weekly\", \"Weekday\": \"Monday\", \"Every\": 2, \"Anchor\": \"2026-01-05\","
                + " \"Days\": 14 }").problems().isEmpty(), "every other week may last two");
    }

    @Test
    void aChildChangesOneLeafOfItsParentsMonthlyRule() {
        CalendarEventAsset parent = CalendarFixtures.event("Traveling_Fair", """
                { "Window": { "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 } }, "FirstYear": 2027 }
                """);
        CalendarEventAsset child = CalendarFixtures.event("Traveling_Fair",
                "{ \"Window\": { \"Rule\": { \"Days\": 3 } } }", parent);
        assertEquals(new RunDays(LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 5)),
                child.annualWindow().runs(2027).get(0), "the child keeps the parent's Type, Weekday and Nth");
    }
}
