package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.calendar.AnnualWindow;
import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.RunDays;

/**
 * The server owner's last word: {@code $Enabled} switches every event off at once, an entry retunes or
 * switches off one, and any mistake in the file costs its overrides, never the server.
 */
class CalendarOwnerLayersTest {

    /** A fair around the fourth Thursday of November: its days move from year to year. */
    private static final String WEEKDAY_FAIR = """
            { "Window": { "Rule": { "Type": "Weekday", "Month": 11, "Weekday": "Thursday", "Nth": 4,
                                    "Before": 6, "After": 5 } }, "FirstYear": 2026 }
            """;

    @TempDir
    Path dir;

    @BeforeEach
    void pointAtTheTempDirectory() {
        CalendarFixtures.reset();
        CalendarOwnerLayers.setDirectory(dir);
        CalendarFixtures.loadDesignEvents();
    }

    @AfterEach
    void clearEverything() {
        CalendarOwnerLayers.setDirectory(CalendarOwnerLayers.DEFAULT_DIRECTORY);
        CalendarFixtures.reset();
    }

    private void write(String json) throws IOException {
        Files.writeString(dir.resolve(CalendarOwnerLayers.FILE), json, StandardCharsets.UTF_8);
    }

    private static CalendarEventAsset resolved(String id) {
        return CalendarEventConfig.getInstance().resolve(id);
    }

    /**
     * A file that reads switches every event off and Hallow's Eve in itself, so the read after it has
     * something to put back: a test starting from the bare switches would pass whether or not it did.
     */
    private void switchEverythingOff() throws IOException {
        write("{ \"$Enabled\": false, \"hallows_eve\": { \"Enabled\": false } }");
        CalendarOwnerLayers.reload();
        assertFalse(CalendarEventConfig.getInstance().isGlobalEnabled(), "the file switched every event off");
        assertFalse(resolved("hallows_eve").isEnabled(), "and Hallow's Eve in itself");
    }

    /** After {@code what}, the owner's switch is on again and Hallow's Eve reads the pack's own switch. */
    private static void assertThePacksAnswersAreBack(String what) {
        assertTrue(CalendarEventConfig.getInstance().isGlobalEnabled(),
                "after " + what + ", every event is switched on again");
        assertTrue(resolved("hallows_eve").isEnabled(),
                "after " + what + ", Hallow's Eve reads the pack's own switch again");
    }

    @Test
    void withNoFileThePacksStandAndEveryEventIsOn() {
        CalendarOwnerLayers.reload();
        assertTrue(CalendarEventConfig.getInstance().isGlobalEnabled());
        assertTrue(resolved("hallows_eve").isEnabled());
    }

    @Test
    void anEntrySwitchesOneEventOffAndKeepsWhatThePackSaid() throws IOException {
        write("{ \"Hallows_Eve\": { \"Enabled\": false } }");
        CalendarOwnerLayers.reload();
        CalendarEventAsset owned = resolved("hallows_eve");
        assertFalse(owned.isEnabled(), "the owner switched it off");
        assertEquals("10-01", owned.windowStart(), "and kept the window the pack authored");
        assertTrue(resolved("harvest_moon").isEnabled(), "the other event is untouched");
    }

    @Test
    void aWindowOverrideNamingOneDayKeepsTheOther() throws IOException {
        write("{ \"harvest_moon\": { \"Window\": { \"End\": \"10-30\" } } }");
        CalendarOwnerLayers.reload();
        assertEquals("10-29", resolved("harvest_moon").windowStart());
        assertEquals("10-30", resolved("harvest_moon").windowEnd());
    }

    @Test
    void theDollarSwitchTurnsEveryEventOffWithoutTouchingTheirFiles() throws IOException {
        write("{ \"$Enabled\": false }");
        CalendarOwnerLayers.reload();
        assertFalse(CalendarEventConfig.getInstance().isGlobalEnabled());
        assertTrue(resolved("hallows_eve").isEnabled(), "each event's own switch is still the pack's");
    }

    @Test
    void aSwitchThatIsNotTrueOrFalseLeavesEveryEventOn() throws IOException {
        write("{ \"$Enabled\": \"no\" }");
        CalendarOwnerLayers.reload();
        assertTrue(CalendarEventConfig.getInstance().isGlobalEnabled());
    }

    @Test
    void aNewerSchemaIsRefusedWholeSwitchIncluded() throws IOException {
        write("{ \"$SchemaVersion\": 99, \"$Enabled\": false, \"hallows_eve\": { \"Enabled\": false } }");
        CalendarOwnerLayers.reload();
        assertTrue(CalendarEventConfig.getInstance().isGlobalEnabled());
        assertTrue(resolved("hallows_eve").isEnabled());
    }

    @Test
    void aMalformedFileCostsItsOverridesNotTheServer() throws IOException {
        write("{ \"$Enabled\": false, ");
        CalendarOwnerLayers.reload();
        assertTrue(CalendarEventConfig.getInstance().isGlobalEnabled());
        assertTrue(resolved("hallows_eve").isEnabled());
    }

    @Test
    void readingTwiceSaysTheSameThing() throws IOException {
        write("{ \"harvest_moon\": { \"Window\": { \"End\": \"10-30\" } } }");
        CalendarOwnerLayers.reload();
        String once = resolved("harvest_moon").windowEnd();
        CalendarOwnerLayers.reload();
        assertEquals(once, resolved("harvest_moon").windowEnd());
        assertEquals("10-29", resolved("harvest_moon").windowStart(),
                "the day the file leaves alone is still the pack's");
    }

    @Test
    void aRereadInheritsFromThePackNotFromTheLastRead() throws IOException {
        write("{ \"harvest_moon\": { \"Window\": { \"End\": \"10-30\" } } }");
        CalendarOwnerLayers.reload();
        assertEquals("10-30", resolved("harvest_moon").windowEnd(), "the first read moved the last day");

        write("{ \"harvest_moon\": { \"Enabled\": false } }");
        CalendarOwnerLayers.reload();
        CalendarEventAsset reread = resolved("harvest_moon");
        assertFalse(reread.isEnabled(), "the second read is in force");
        assertEquals("10-31", reread.windowEnd(),
                "and its entry inherits the last day from the pack, not from what the first read produced");
    }

    @Test
    void emptyingTheFileGivesThePackItsEventsBack() throws IOException {
        write("{ \"$Enabled\": false, \"hallows_eve\": { \"Enabled\": false } }");
        CalendarOwnerLayers.reload();
        write("{ }");
        CalendarOwnerLayers.reload();
        assertTrue(CalendarEventConfig.getInstance().isGlobalEnabled());
        assertTrue(resolved("hallows_eve").isEnabled());
    }

    @Test
    void aMalformedFileAfterASwitchOffPutsThePacksAnswersBack() throws IOException {
        switchEverythingOff();
        write("{ \"$Enabled\": false, ");
        CalendarOwnerLayers.reload();
        assertThePacksAnswersAreBack("a malformed file");
    }

    @Test
    void aNewerSchemaAfterASwitchOffPutsThePacksAnswersBack() throws IOException {
        switchEverythingOff();
        write("{ \"$SchemaVersion\": 99, \"$Enabled\": false, \"hallows_eve\": { \"Enabled\": false } }");
        CalendarOwnerLayers.reload();
        assertThePacksAnswersAreBack("a newer-schema file");
    }

    @Test
    void aDeletedFileAfterASwitchOffPutsThePacksAnswersBack() throws IOException {
        switchEverythingOff();
        Files.delete(dir.resolve(CalendarOwnerLayers.FILE));
        CalendarOwnerLayers.reload();
        assertThePacksAnswersAreBack("a deleted file");
    }

    @Test
    void anOwnerCanAddAnEventNoPackShips() throws IOException {
        write("{ \"Owner_Fair\": { \"Window\": { \"Start\": \"06-01\", \"End\": \"06-07\" }, \"FirstYear\": 2026 } }");
        CalendarOwnerLayers.reload();
        CalendarEventAsset added = resolved("owner_fair");
        assertNotNull(added);
        assertTrue(added.canRun());
    }

    @Test
    void anOwnersFixedRulePinsAnEventWhoseDaysMove() throws IOException {
        CalendarFixtures.loadEvents(Map.of("harvest_fair", CalendarFixtures.event("Harvest_Fair", WEEKDAY_FAIR)));
        write("{ \"Harvest_Fair\": { \"Window\": { \"Rule\": { \"Type\": \"Fixed\", \"Start\": \"11-20\","
                + " \"End\": \"12-01\" } } } }");
        CalendarOwnerLayers.reload();
        AnnualWindow pinned = resolved("harvest_fair").annualWindow();
        assertNotNull(pinned);
        assertFalse(pinned.moves(), "the owner's Fixed rule wins: the same days every year");
        assertEquals(new RunDays(LocalDate.of(2027, 11, 20), LocalDate.of(2027, 12, 1)), pinned.days(2027),
                "where the pack's rule would have run November 19th to 30th");
        assertTrue(resolved("harvest_fair").notes().isEmpty());
    }

    @Test
    void anOwnerChangingOneLeafOfTheRuleKeepsTheRest() throws IOException {
        CalendarFixtures.loadEvents(Map.of("egg_hunt", CalendarFixtures.event("Egg_Hunt",
                "{ \"Window\": { \"Rule\": { \"Type\": \"Easter\", \"Before\": 10, \"After\": 7 } }, \"FirstYear\": 2027 }")));
        write("{ \"Egg_Hunt\": { \"Window\": { \"Rule\": { \"After\": 9 } } } }");
        CalendarOwnerLayers.reload();
        assertEquals(new RunDays(LocalDate.of(2027, 3, 18), LocalDate.of(2027, 4, 6)),
                resolved("egg_hunt").annualWindow().days(2027));
    }

    @Test
    void anOwnersStartAndEndBesideAPacksRuleAreNotedAndUnused() throws IOException {
        CalendarFixtures.loadEvents(Map.of("harvest_fair", CalendarFixtures.event("Harvest_Fair", WEEKDAY_FAIR)));
        write("{ \"Harvest_Fair\": { \"Window\": { \"Start\": \"11-18\", \"End\": \"11-30\" } } }");
        CalendarOwnerLayers.reload();
        CalendarEventAsset owned = resolved("harvest_fair");
        assertEquals(List.of(CalendarEventAsset.NOTE_START_END_IGNORED), owned.notes(),
                "the owner learns to write a Fixed rule instead");
        assertEquals(new RunDays(LocalDate.of(2027, 11, 19), LocalDate.of(2027, 11, 30)), owned.annualWindow().days(2027),
                "the pack's rule still dates the run");
    }

    // A re-date never renumbers (the maintainer's ruling): an owner moving a monthly rule to another day of each
    // month leaves every run its number, so a player's record of "run 10" still names October's run.
    @Test
    void anOwnerReDatingAMonthlyRuleKeepsEveryRunsNumber() throws IOException {
        CalendarFixtures.loadEvents(Map.of("traveling_fair", CalendarFixtures.event("Traveling_Fair", """
                { "Window": { "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 } }, "FirstYear": 2026 }
                """)));
        CalendarOwnerLayers.reload();
        assertEquals(new AnnualWindow.DatedRun(2026, 10, new RunDays(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10))),
                resolved("traveling_fair").annualWindow().runContaining(CalendarFixtures.at("2026-10-05T12:00:00Z"),
                        ZoneOffset.UTC), "the pack's October run");
        write("{ \"Traveling_Fair\": { \"Window\": { \"Rule\": { \"Weekday\": \"Saturday\", \"Nth\": 2, \"Days\": 3 } } } }");
        CalendarOwnerLayers.reload();
        AnnualWindow owned = resolved("traveling_fair").annualWindow();
        assertEquals(new AnnualWindow.DatedRun(2026, 10, new RunDays(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12))),
                owned.runContaining(CalendarFixtures.at("2026-10-11T12:00:00Z"), ZoneOffset.UTC),
                "re-dated to the second Saturday, October's run is still run 10");
        for (int number = 1; number <= 12; number++) {
            assertEquals(Month.of(number), owned.run(2026, number).first().getMonth(),
                    "run " + number + " is still its month's");
        }
    }

    @Test
    void anOwnersYearsEntryMergesWithThePacksByYear() throws IOException {
        CalendarFixtures.loadEvents(Map.of("egg_hunt", CalendarFixtures.event("Egg_Hunt", """
                { "Window": { "Rule": { "Type": "Easter", "Before": 10, "After": 7 },
                              "Years": { "2031": { "Start": "04-01", "End": "04-20" },
                                         "2032": { "Start": "03-25", "End": "04-10" } } }, "FirstYear": 2027 }
                """)));
        write("{ \"Egg_Hunt\": { \"Window\": { \"Years\": { \"2031\": { \"Start\": \"04-02\" } } } } }");
        CalendarOwnerLayers.reload();
        AnnualWindow window = resolved("egg_hunt").annualWindow();
        assertEquals(new RunDays(LocalDate.of(2031, 4, 2), LocalDate.of(2031, 4, 20)), window.days(2031),
                "one leaf of one year moves; that year keeps its End");
        assertEquals(new RunDays(LocalDate.of(2032, 3, 25), LocalDate.of(2032, 4, 10)), window.days(2032),
                "a year the owner did not name keeps the pack's days");
    }
}
