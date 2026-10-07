package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * One case per code, each driven through the pure core with fakes for the item store and the lang
 * catalogue; the rules around the codes (a switched-off event is skipped, every finding is filed under
 * the {@code calendar} domain); and the engine walk's one rule of its own, that a calendar the owner
 * switched off reports nothing.
 */
class CalendarEventValidatorTest {

    private static final Predicate<String> ALL_ITEMS = id -> true;
    private static final Predicate<String> ALL_KEYS = key -> true;

    private static final String SOUND = """
            { "Window": { "Start": "03-20", "End": "03-27" }, "FirstYear": 2026,
              "Presentation": { "TitleKey": "calendar.spring_fair.name", "FlavorKey": "calendar.spring_fair.flavor",
                                "Icon": "Spring_Ribbon" },
              "Herald": { "Start": { "TitleKey": "calendar.spring_fair.start", "SubtitleKey": "calendar.spring_fair.sub" },
                          "End": { "TitleKey": "calendar.spring_fair.end" } } }
            """;

    @AfterEach
    void bareCalendar() {
        CalendarFixtures.reset();
    }

    @Nonnull
    private static List<Finding> audit(@Nonnull List<CalendarEventAsset> events, @Nonnull List<CalendarSpawnAsset> spawns,
            @Nonnull Predicate<String> itemKnown, @Nonnull Predicate<String> keyShipped) {
        return CalendarEventValidator.audit(events, spawns, itemKnown, keyShipped);
    }

    @Nonnull
    private static List<Finding> audit(@Nonnull CalendarEventAsset... events) {
        return audit(List.of(events), List.of(), ALL_ITEMS, ALL_KEYS);
    }

    @Nonnull
    private static List<String> codes(@Nonnull List<Finding> findings) {
        List<String> out = new ArrayList<>();
        for (Finding finding : findings) {
            out.add(finding.code());
        }
        return out;
    }

    @Nonnull
    private static Finding only(@Nonnull List<Finding> findings, @Nonnull String code) {
        List<Finding> matching = findings.stream().filter(f -> f.code().equals(code)).toList();
        assertEquals(1, matching.size(), () -> "expected one " + code + " finding, got " + findings);
        return matching.get(0);
    }

    @Test
    void aSoundEventAndItsSpawnReportNothing() {
        CalendarEventAsset fair = CalendarFixtures.event("Spring_Fair", SOUND);
        CalendarSpawnAsset rabbits = CalendarFixtures.spawn("Spring_Fair_Rabbits",
                "{ \"Event\": \"Spring_Fair\", \"Spawn\": { \"Environments\": [\"Env_Test_Meadow\"] } }");

        assertTrue(audit(List.of(fair), List.of(rabbits), ALL_ITEMS, ALL_KEYS).isEmpty());
    }

    @Test
    void whatStopsAnEventRunningIsAnErrorUnderItsOwnCodeAndSaysWhy() {
        List<Finding> findings = audit(CalendarFixtures.event("Broken_Fair", "{ \"Clock\": \"Mars/Olympus\" }"));

        assertEquals(List.of(CalendarEventAsset.PROBLEM_WINDOW_MISSING, CalendarEventAsset.PROBLEM_FIRST_YEAR_MISSING,
                CalendarEventAsset.PROBLEM_CLOCK_UNKNOWN), codes(findings));
        Finding window = only(findings, CalendarEventAsset.PROBLEM_WINDOW_MISSING);
        assertEquals(Severity.ERROR, window.severity());
        assertEquals("broken_fair", window.sourceId());
        assertTrue(window.message().contains("has no Window, so it never runs"), window.message());
        assertEquals(Severity.ERROR, only(findings, CalendarEventAsset.PROBLEM_FIRST_YEAR_MISSING).severity());
        assertEquals(Severity.WARNING, only(findings, CalendarEventAsset.PROBLEM_CLOCK_UNKNOWN).severity(),
                "an unknown Clock still runs, on UTC");
    }

    @Test
    void anUnreadableWindowAndAFirstYearOutOfRangeAreErrors() {
        List<Finding> findings = audit(CalendarFixtures.event("Odd_Fair",
                "{ \"Window\": { \"Start\": \"02-30\", \"End\": \"03-01\" }, \"FirstYear\": 1066 }"));

        assertEquals(Severity.ERROR, only(findings, CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE).severity());
        assertEquals(Severity.ERROR, only(findings, CalendarEventAsset.PROBLEM_FIRST_YEAR_OUT_OF_RANGE).severity());
    }

    @Test
    void aRuleThatCouldStartARunInTheYearBeforeIsAnErrorAndASetAsideYearsEntryAWarning() {
        List<Finding> findings = audit(
                CalendarFixtures.event("Early_Hunt",
                        "{ \"Window\": { \"Rule\": { \"Type\": \"Easter\", \"Before\": 81 } }, \"FirstYear\": 2027 }"),
                CalendarFixtures.event("Dated_Fair", """
                        { "Window": { "Start": "06-01", "End": "06-07",
                                      "Years": { "2025": { "Start": "06-02", "End": "06-08" } } }, "FirstYear": 2026 }
                        """));

        Finding invalid = only(findings, CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID);
        assertEquals(Severity.ERROR, invalid.severity(), "the event never runs");
        assertEquals("early_hunt", invalid.sourceId());
        assertTrue(invalid.message().contains("so it never runs"), invalid.message());
        Finding ignored = only(findings, CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED);
        assertEquals(Severity.WARNING, ignored.severity(), "the event still runs; only that entry is set aside");
        assertEquals("dated_fair", ignored.sourceId());
    }

    @Test
    void anIdAnotherSwitchUsesOrTheAttendanceRecordCannotSaveIsAnError() {
        List<Finding> findings = audit(CalendarFixtures.event("Almanac", CalendarFixtures.HARVEST_MOON),
                CalendarFixtures.event("Spring|Fair", CalendarFixtures.HARVEST_MOON));

        assertEquals(Severity.ERROR, only(findings, CalendarEventAsset.PROBLEM_ID_RESERVED).severity());
        assertEquals(Severity.ERROR, only(findings, CalendarEventAsset.PROBLEM_ID_UNSAVABLE).severity());
    }

    @Test
    void anEventSwitchedOffInItsOwnFileIsSkipped() {
        assertTrue(audit(CalendarFixtures.event("Off_Fair", "{ \"Enabled\": false, \"Clock\": \"Mars/Olympus\" }"))
                .isEmpty());
    }

    @Test
    void anIconNoLoadedItemShipsIsAWarning() {
        CalendarEventAsset fair = CalendarFixtures.event("Spring_Fair", SOUND);

        List<Finding> findings = audit(List.of(fair), List.of(), "Spring_Ribbon"::equals, ALL_KEYS);

        assertTrue(findings.isEmpty(), "the icon is known: " + findings);
        Finding icon = only(audit(List.of(fair), List.of(), id -> false, ALL_KEYS), CalendarEventValidator.UNKNOWN_ICON);
        assertEquals(Severity.WARNING, icon.severity());
        assertTrue(icon.message().contains("'Spring_Ribbon'"), icon.message());
    }

    @Test
    void everyKeyTheFileNamesThatNoLangFileShipsIsAWarning() {
        CalendarEventAsset fair = CalendarFixtures.event("Spring_Fair", SOUND);

        List<Finding> findings = audit(List.of(fair), List.of(), ALL_ITEMS, key -> key.endsWith(".name"));

        assertEquals(List.of(TextKeyAudit.UNKNOWN_TEXT_KEY, TextKeyAudit.UNKNOWN_TEXT_KEY,
                TextKeyAudit.UNKNOWN_TEXT_KEY, TextKeyAudit.UNKNOWN_TEXT_KEY), codes(findings),
                "FlavorKey, the start banner's title and subtitle, and the end banner's title");
        for (Finding finding : findings) {
            assertEquals(Severity.WARNING, finding.severity());
            assertEquals("spring_fair", finding.sourceId());
        }
    }

    @Test
    void aBannerWithNoTitleShowsNothingAndSaysSo() {
        CalendarEventAsset fair = CalendarFixtures.event("Quiet_Fair", """
                { "Window": { "Start": "03-20", "End": "03-27" }, "FirstYear": 2026,
                  "Herald": { "Start": { "SubtitleKey": "calendar.quiet_fair.sub", "Major": true }, "End": { } } }
                """);

        List<Finding> findings = audit(fair);

        assertEquals(List.of(CalendarEventValidator.HERALD_WITHOUT_TITLE, CalendarEventValidator.HERALD_WITHOUT_TITLE),
                codes(findings));
        assertTrue(findings.get(0).message().contains("Herald.Start"), findings.get(0).message());
        assertTrue(findings.get(1).message().contains("Herald.End"), findings.get(1).message());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    @Test
    void aSpawnThatCanNeverWriteItsRuleIsAnError() {
        List<Finding> findings = audit(List.of(), List.of(
                CalendarFixtures.spawn("No_Event", "{ \"Spawn\": { \"Environments\": [\"Env_Test_Meadow\"] } }"),
                CalendarFixtures.spawn("No_Body", "{ \"Event\": \"Spring_Fair\" }")), ALL_ITEMS, ALL_KEYS);

        Finding noEvent = only(findings, CalendarEventValidator.SPAWN_NO_EVENT);
        assertEquals(Severity.ERROR, noEvent.severity());
        assertEquals("no_event", noEvent.sourceId());
        assertEquals(Severity.ERROR, only(findings, CalendarEventValidator.SPAWN_NO_RULE_BODY).severity());
    }

    @Test
    void aSpawnRidingAnEventNothingLoadsIsAWarningAndOneSwitchedOffIsNot() {
        CalendarEventAsset off = CalendarFixtures.event("Off_Fair", "{ \"Enabled\": false }");
        String body = ", \"Spawn\": { \"Environments\": [\"Env_Test_Meadow\"] } }";

        List<Finding> findings = audit(List.of(off), List.of(
                CalendarFixtures.spawn("Typo_Rabbits", "{ \"Event\": \"Sping_Fair\"" + body),
                CalendarFixtures.spawn("Off_Rabbits", "{ \"Event\": \"OFF_FAIR\"" + body)), ALL_ITEMS, ALL_KEYS);

        Finding unknown = only(findings, CalendarEventValidator.SPAWN_UNKNOWN_EVENT);
        assertEquals(Severity.WARNING, unknown.severity());
        assertEquals("typo_rabbits", unknown.sourceId());
        assertTrue(unknown.message().contains("'sping_fair'"), unknown.message());
        assertEquals(1, findings.size(), "a spawn riding a switched-off event is not unknown: " + findings);
    }

    @Test
    void everyFindingIsFiledUnderTheCalendarDomain() {
        List<Finding> findings = audit(List.of(CalendarFixtures.event("Bare", "{ \"Herald\": { \"End\": { } } }")),
                List.of(CalendarFixtures.spawn("Bare_Spawn", "{ }")), id -> false, key -> false);

        assertTrue(findings.size() >= 4, "the bare files have plenty wrong: " + findings);
        for (Finding finding : findings) {
            assertEquals(CalendarEventValidator.DOMAIN, finding.domain());
        }
    }

    @Test
    void theEngineWalkReadsTheFoldAndReportsNothingWhileTheOwnerHasEveryEventOff() {
        CalendarFixtures.loadEvents(Map.of("broken_fair", CalendarFixtures.event("Broken_Fair", "{ }")));

        assertTrue(codes(CalendarEventValidator.audit()).contains(CalendarEventAsset.PROBLEM_WINDOW_MISSING));

        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertTrue(CalendarEventValidator.audit().isEmpty(), "off means absent: there is nothing to report");
    }
}
