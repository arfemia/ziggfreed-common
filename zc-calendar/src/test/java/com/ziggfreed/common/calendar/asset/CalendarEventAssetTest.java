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
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.AnnualWindow;
import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.RunDays;
import com.ziggfreed.common.occurrence.Occurrence;

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

    private static RunDays days(String first, String last) {
        return new RunDays(LocalDate.parse(first), LocalDate.parse(last));
    }

    /** The numbers of {@code year}'s runs as the window keeps them, in order. */
    private static List<Integer> numbers(AnnualWindow window, int year) {
        return window.datedRuns(year).stream().map(AnnualWindow.DatedRun::number).toList();
    }

    // Several runs a year from one Fixed Rule. A list's run is its place in the list (the maintainer's ruling), so
    // the span written second is run 2 whatever its days; Runs wins over Start and End.
    @Test
    void aFixedRuleWithRunsDatesSeveralRunsAYearAndRunsWinOverStartAndEnd() {
        CalendarEventAsset fairs = CalendarFixtures.event("Two_Fairs", """
                { "Window": { "Rule": { "Type": "Fixed", "Start": "05-01", "End": "05-02",
                                        "Runs": [ { "Start": "09-20", "End": "09-26" },
                                                  { "Start": "04-10", "End": "04-16" } ] } }, "FirstYear": 2026 }
                """);
        assertTrue(fairs.problems().isEmpty(), fairs.problems().toString());
        assertEquals(List.of(days("2026-09-20", "2026-09-26"), days("2026-04-10", "2026-04-16")),
                fairs.annualWindow().runs(2026), "numbered by their place in the list; Runs wins over Start and End");
        assertEquals(days("2026-04-10", "2026-04-16"), fairs.annualWindow().run(2026, 2),
                "the spring span, written second, is run 2 though it starts first");
        assertEquals(List.of(CalendarEventAsset.NOTE_START_END_BESIDE_RUNS), fairs.notes());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Fixed\", \"Runs\": [] }").problems(), "a rule that dates no run at all");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Fixed\", \"Runs\": [ { \"Start\": \"06-31\", \"End\": \"07-02\" } ] }").problems());
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID),
                rule("{ \"Type\": \"Fixed\", \"Runs\": [ { \"Start\": \"02-29\", \"End\": \"02-28\" } ] }").problems(),
                "a span that meets its own next run");
    }

    /** {@code count} one-day spans as a {@code Runs} list, three days apart from January 1st, so none meets another. */
    private static String spans(int count) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            LocalDate day = LocalDate.of(2026, 1, 1).plusDays(3L * i);
            String monthDay = String.format(Locale.ROOT, "%02d-%02d", day.getMonthValue(), day.getDayOfMonth());
            out.append(i == 0 ? "" : ", ").append("{ \"Start\": \"").append(monthDay)
                    .append("\", \"End\": \"").append(monthDay).append("\" }");
        }
        return out.append(']').toString();
    }

    // A run's number is at most Occurrence.MAX_NUMBER, so a reader can keep a year's runs as a set of numbers: a list
    // of spans, which numbers each run by its place, holds no more.
    @Test
    void aListOfRunsHoldsAtMostTheHighestRunNumber() {
        CalendarEventAsset full = rule("{ \"Type\": \"Fixed\", \"Runs\": " + spans(Occurrence.MAX_NUMBER) + " }");
        assertTrue(full.problems().isEmpty(), full.problems().toString());
        assertEquals(Occurrence.MAX_NUMBER, numbers(full.annualWindow(), 2026).get(Occurrence.MAX_NUMBER - 1),
                "the last span is the highest number a run takes");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE),
                rule("{ \"Type\": \"Fixed\", \"Runs\": " + spans(Occurrence.MAX_NUMBER + 1) + " }").problems(),
                "one span more cannot be read");
        CalendarEventAsset entry = CalendarFixtures.event("Fair", "{ \"Window\": { \"Start\": \"12-10\", \"End\": "
                + "\"12-12\", \"Years\": { \"2027\": { \"Runs\": " + spans(Occurrence.MAX_NUMBER + 1) + " } } }, "
                + "\"FirstYear\": 2026 }");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED), entry.problems(),
                "nor can a Years entry's, which costs that entry alone");
        assertEquals(days("2027-12-10", "2027-12-12"), entry.annualWindow().days(2027));
        assertTrue(CalendarEventConfig.sentence(CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE)
                .contains(Integer.toString(Occurrence.MAX_NUMBER)), "the log says how many spans a list may hold");
    }

    @Test
    void aYearsEntryDatesItsWholeYearWithSeveralRunsOrNone() {
        CalendarEventAsset fair = CalendarFixtures.event("Traveling_Fair", """
                { "Window": { "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 },
                              "Years": { "2028": { "Runs": [ { "Start": "05-01", "End": "05-07" },
                                                             { "Start": "11-01", "End": "11-07" } ] },
                                         "2029": { "Runs": [] } } }, "FirstYear": 2027 }
                """);
        assertTrue(fair.problems().isEmpty(), fair.problems().toString());
        assertEquals(2, fair.annualWindow().runs(2028).size(), "the entry's runs are 2028's only runs");
        assertTrue(fair.annualWindow().runs(2029).isEmpty(), "an empty list is no run that year");
        assertEquals(12, fair.annualWindow().runs(2030).size(), "and the Rule again after it");
        assertEquals(List.of(CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED), CalendarFixtures.event("Fair", """
                { "Window": { "Start": "06-01", "End": "06-07",
                              "Years": { "2027": { "Runs": [ { "Start": "06-31", "End": "07-02" } ] } } },
                  "FirstYear": 2026 }
                """).problems(), "an unreadable span costs that entry alone");
        CalendarEventAsset leap = CalendarFixtures.event("Fair", """
                { "Window": { "Start": "06-01", "End": "06-07",
                              "Years": { "2028": { "Runs": [ { "Start": "02-29", "End": "02-28" } ] } } },
                  "FirstYear": 2026 }
                """);
        assertEquals(List.of(CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED), leap.problems(),
                "a span from February 29th through February 28th is refused");
        assertEquals(days("2028-06-01", "2028-06-07"), leap.annualWindow().days(2028), "and the year keeps Start and End");
        assertTrue(CalendarEventConfig.sentence(CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED).contains("February 29th"),
                "the log says such a span is refused");
    }

    @Test
    void runsThatMeetAreSetAsideAndEachIsNamedOnce() {
        CalendarEventAsset overlapping = CalendarFixtures.event("Two_Fairs", """
                { "Window": { "Rule": { "Type": "Fixed", "Runs": [ { "Start": "09-01", "End": "09-25" },
                                                                  { "Start": "09-20", "End": "09-26" } ] } },
                  "FirstYear": 2026 }
                """);
        assertEquals(List.of(CalendarEventAsset.PROBLEM_RUN_SET_ASIDE), overlapping.problems());
        assertTrue(overlapping.canRun(), "the first run is always kept");
        assertEquals(1, overlapping.setAside().size(), "set aside every year, named once");
        assertEquals(new AnnualWindow.SetAside(2026, 2, days("2026-09-20", "2026-09-26"), days("2026-09-01", "2026-09-25")),
                overlapping.setAside().get(0), "named by its place in the list, in the first year it is set aside");

        CalendarEventAsset crossing = CalendarFixtures.event("Two_Fairs", """
                { "Window": { "Rule": { "Type": "Fixed", "Runs": [ { "Start": "01-05", "End": "01-11" },
                                                                  { "Start": "06-01", "End": "06-07" } ] },
                              "Years": { "2027": { "Runs": [ { "Start": "12-20", "End": "01-08" } ] } } },
                  "FirstYear": 2026 }
                """);
        assertEquals(List.of(CalendarEventAsset.PROBLEM_RUN_SET_ASIDE), crossing.problems());
        assertEquals(1, crossing.setAside().size());
        assertEquals(2028, crossing.setAside().get(0).year(), "the year after a listed year is read too");
        assertEquals(days("2028-01-05", "2028-01-11"), crossing.setAside().get(0).run());
        assertEquals(1, crossing.setAside().get(0).number());

        CalendarEventAsset twice = CalendarFixtures.event("Two_Fairs", """
                { "Window": { "Rule": { "Type": "Fixed", "Runs": [ { "Start": "09-01", "End": "09-25" },
                                                                  { "Start": "09-20", "End": "09-26" },
                                                                  { "Start": "09-20", "End": "09-26" } ] } },
                  "FirstYear": 2026 }
                """);
        assertEquals(List.of(2, 3), twice.setAside().stream().map(AnnualWindow.SetAside::number).toList(),
                "two runs on the same days are two runs set aside");
        CalendarEventAsset leap = CalendarFixtures.event("Two_Fairs", """
                { "Window": { "Rule": { "Type": "Fixed", "Runs": [ { "Start": "02-20", "End": "03-01" },
                                                                  { "Start": "02-29", "End": "03-05" } ] } },
                  "FirstYear": 2027 }
                """);
        assertEquals(1, leap.setAside().size(),
                "a span set aside in common and leap years alike is one span, though February 29th falls back");
        for (String code : List.of(CalendarEventAsset.PROBLEM_RUN_SET_ASIDE,
                CalendarEventAsset.NOTE_START_END_BESIDE_RUNS, CalendarEventAsset.PROBLEM_SKIP_UNKNOWN_RUN)) {
            assertFalse(CalendarEventConfig.sentence(code).startsWith("has a problem"),
                    code + " is told in a sentence of its own");
        }
    }

    // The maintainer's skip list: a Years entry of Skip alone keeps the Rule's runs that year less those numbers,
    // and a number skipped is never handed to another run.
    @Test
    void aYearsEntryOfSkipAloneKeepsTheRulesRunsLessThoseNumbers() {
        CalendarEventAsset market = CalendarFixtures.event("Market", """
                { "Window": { "Rule": { "Type": "Monthly", "Day": 15, "Days": 2 },
                              "Years": { "2027": { "Skip": [3, 7] } } }, "FirstYear": 2026 }
                """);
        assertTrue(market.problems().isEmpty(), market.problems().toString());
        AnnualWindow window = market.annualWindow();
        assertEquals(List.of(1, 2, 4, 5, 6, 8, 9, 10, 11, 12), numbers(window, 2027),
                "March and July are left out, and every other month keeps its number");
        assertNull(window.run(2027, 3));
        assertNull(window.runContaining(CalendarFixtures.at("2027-03-15T12:00:00Z"), ZoneOffset.UTC));
        assertEquals(12, window.runs(2026).size(), "the years around it keep every run");
        assertEquals(12, window.runs(2028).size());
        assertEquals(new AnnualWindow.DatedRun(2027, 4, days("2027-04-15", "2027-04-16")), window.after(2027, 3),
                "after a skipped run comes the year's next, sought from where the skipped one falls");
        assertTrue(window.moves(), "a year that skips runs differs from the next");
        assertTrue(market.unknownSkips().isEmpty());
    }

    @Test
    void aSkipBesideAnEntrysOwnDaysLeavesOutThoseOfItsPlaces() {
        CalendarEventAsset fair = CalendarFixtures.event("Fair", """
                { "Window": { "Start": "06-01", "End": "06-07",
                              "Years": { "2027": { "Runs": [ { "Start": "04-10", "End": "04-16" },
                                                             { "Start": "06-01", "End": "06-07" },
                                                             { "Start": "09-20", "End": "09-26" } ], "Skip": [1] },
                                         "2028": { "Start": "06-02", "End": "06-08", "Skip": [1] } } },
                  "FirstYear": 2026 }
                """);
        assertTrue(fair.problems().isEmpty(), fair.problems().toString());
        AnnualWindow window = fair.annualWindow();
        assertEquals(List.of(new AnnualWindow.DatedRun(2027, 2, days("2027-06-01", "2027-06-07")),
                new AnnualWindow.DatedRun(2027, 3, days("2027-09-20", "2027-09-26"))), window.datedRuns(2027),
                "the entry's own runs, numbered by place, less the first");
        assertEquals(new AnnualWindow.DatedRun(2027, 2, days("2027-06-01", "2027-06-07")), window.after(2027, 1),
                "after the skipped April run comes June's, sought from April's own days");
        assertFalse(window.hasRun(2028), "skipping a year's one run leaves it none");
        assertEquals(new AnnualWindow.DatedRun(2029, 1, days("2029-06-01", "2029-06-07")), window.after(2027, 3),
                "after 2027's last run, the next year that has one");
    }

    @Test
    void aSkipOfARunTheYearDoesNotHaveIsAProblemThatNeverStopsTheEvent() {
        CalendarEventAsset market = CalendarFixtures.event("Market", """
                { "Window": { "Rule": { "Type": "Monthly", "Day": 15, "Days": 2, "Months": [3, 12] },
                              "Years": { "2027": { "Skip": [13, 12, 5, 0] },
                                         "2028": { "Runs": [ { "Start": "06-01", "End": "06-07" } ], "Skip": [2] } } },
                  "FirstYear": 2026 }
                """);
        assertEquals(List.of(CalendarEventAsset.PROBLEM_SKIP_UNKNOWN_RUN), market.problems());
        assertTrue(market.canRun(), "a skip that leaves nothing out costs nothing");
        assertEquals(Map.of(2027, List.of(0, 5, 13), 2028, List.of(2)), market.unknownSkips(),
                "May is not among its Months, 0 and 13 are no month, and 2028's list has one run");
        assertEquals(List.of(3), numbers(market.annualWindow(), 2027), "December's run 12 is still skipped");
        assertEquals(new AnnualWindow.DatedRun(2029, 3, days("2029-03-15", "2029-03-16")),
                market.annualWindow().after(2028, 2), "a place the list lacks names no date: the next year's earliest");
        CalendarEventAsset mondays = CalendarFixtures.event("Mondays", """
                { "Window": { "Rule": { "Type": "Weekly", "Weekday": "Monday" },
                              "Years": { "2027": { "Skip": [1, 2] } } }, "FirstYear": 2026 }
                """);
        assertEquals(Map.of(2027, List.of(1)), mondays.unknownSkips(),
                "2027 begins on a Friday, so its week 1 holds no Monday");
        assertEquals(3, numbers(mondays.annualWindow(), 2027).get(0), "and week 2's Monday is skipped");
    }
}
