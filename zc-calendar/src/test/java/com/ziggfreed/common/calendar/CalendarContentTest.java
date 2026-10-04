package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.ziggfreed.common.calendar.asset.CalendarOwnerLayers;
import com.ziggfreed.common.factor.FeatureFlags;

/** A pack reload folds the pack, then the owner file, then declares the arriving events' features. */
class CalendarContentTest {

    @TempDir
    Path ownerDir;

    @BeforeEach
    void setUp() {
        CalendarFixtures.reset();
        CalendarOwnerLayers.setDirectory(ownerDir);
    }

    @AfterEach
    void tearDown() {
        CalendarOwnerLayers.setDirectory(CalendarOwnerLayers.DEFAULT_DIRECTORY);
        CalendarRuntime.useClockForTests(null);
        FeatureFlags.reset();
        CalendarFixtures.reset();
    }

    @Test
    void reloadingEventsFoldsThePackThenTheOwnerFileAndDeclaresTheirFeatures() throws IOException {
        Files.writeString(ownerDir.resolve(CalendarOwnerLayers.FILE), "{ \"Harvest_Moon\": { \"Enabled\": false } }",
                StandardCharsets.UTF_8);
        CalendarRuntime.useClockForTests(() -> at("2026-10-30T22:00:00Z"));
        CalendarContent.reloadEvents(Map.of(
                "hallows_eve", CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE),
                "harvest_moon", CalendarFixtures.event("Harvest_Moon", CalendarFixtures.HARVEST_MOON)));
        assertTrue(CalendarRuntime.service().isEnabled("hallows_eve"));
        assertFalse(CalendarRuntime.service().isEnabled("harvest_moon"), "the owner file was read after the pack");
        assertEquals(1.0, FeatureFlags.read("ziggfreedcommon", "Hallows_Eve_Live"),
                "an arriving event's features are declared as it arrives");
        assertEquals(0.0, FeatureFlags.read("ziggfreedcommon", "Harvest_Moon"));
    }
}
