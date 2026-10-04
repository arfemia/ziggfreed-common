package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.calendar.CalendarFixtures;

/**
 * The server owner's last word: {@code $Enabled} switches every event off at once, an entry retunes or
 * switches off one, and any mistake in the file costs its overrides, never the server.
 */
class CalendarOwnerLayersTest {

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
}
