package com.ziggfreed.common.almanac.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacSwitch;

/** The server owner's word on the Almanac: the switch, and per-season overrides over the packs' pages. */
class AlmanacOwnerLayersTest {

    @TempDir
    Path dir;

    @BeforeEach
    void point() {
        AlmanacOwnerLayers.setDirectory(dir);
        AlmanacSwitch.resetForTests();
    }

    @AfterEach
    void restore() {
        AlmanacOwnerLayers.setDirectory(AlmanacOwnerLayers.DEFAULT_DIRECTORY);
        AlmanacSwitch.resetForTests();
        AlmanacEntryConfig.getInstance().mergeOwnerLayer(Map.of());
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of());
    }

    private void write(String body) throws IOException {
        Files.writeString(dir.resolve(AlmanacOwnerLayers.FILE), body, StandardCharsets.UTF_8);
    }

    @Test
    void withNoFileTheAlmanacIsOn() {
        AlmanacOwnerLayers.reload();
        assertTrue(AlmanacSwitch.isOn());
    }

    @Test
    void theOwnerSwitchTurnsItOff() throws IOException {
        write("{ \"$Enabled\": false }");
        AlmanacOwnerLayers.readSwitch();
        assertFalse(AlmanacSwitch.isOn());

        write("{ \"$Enabled\": true }");
        AlmanacOwnerLayers.readSwitch();
        assertTrue(AlmanacSwitch.isOn(), "a re-read turns it back on");
    }

    @Test
    void aFileFromANewerSchemaIsRefusedWholeSoTheAlmanacStaysOn() throws IOException {
        write("{ \"$SchemaVersion\": 99, \"$Enabled\": false }");
        AlmanacOwnerLayers.readSwitch();
        assertTrue(AlmanacSwitch.isOn());
    }

    @Test
    void anUnreadableFileOrAWrongTypedSwitchLeavesItOn() throws IOException {
        write("{ not json");
        AlmanacOwnerLayers.readSwitch();
        assertTrue(AlmanacSwitch.isOn());

        write("{ \"$Enabled\": \"no\" }");
        AlmanacOwnerLayers.readSwitch();
        assertTrue(AlmanacSwitch.isOn());
    }

    @Test
    void anOwnerEntryRetunesOneLeafAndKeepsThePacksLines() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));
        write("{ \"$Enabled\": true, \"Test_Season\": { \"Order\": 99 } }");

        AlmanacOwnerLayers.reload();

        AlmanacEntryAsset resolved = AlmanacEntryConfig.getInstance().resolve("test_season");
        assertNotNull(resolved);
        assertEquals(99, resolved.orderOrLast());
        assertEquals(2, resolved.getStats().size(), "the owner's one leaf keeps the pack's lines");
        assertEquals(1, AlmanacEntryConfig.getInstance().index().forKind("KILL_ENTITY").size(),
                "the stat index follows the owner layer");
        assertTrue(AlmanacSwitch.isOn());
    }
}
