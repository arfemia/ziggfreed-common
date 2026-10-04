package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;

/** The two calendar readings, their fail-closed edges, and the names every requirement line shows. */
class CalendarFactorsTest {

    private static final String LANG = "ziggfreedcommon.calendar.lang";
    private static final String KEY_PREFIX = "ziggfreedcommon.calendar.";

    private final CalendarService service =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance());

    @BeforeEach
    void load() {
        CalendarFixtures.reset();
        CalendarFixtures.loadDesignEvents();
    }

    @AfterEach
    void reset() {
        CalendarFixtures.reset();
    }

    @Test
    void liveReadsOneWhileRunningZeroBetweenItsDatesAndNothingForAnEventTheServerLacks() {
        long october = at("2026-10-02T12:00:00Z");
        assertEquals(1.0, CalendarFactors.live(service, "Hallows_Eve", october));
        assertEquals(0.0, CalendarFactors.live(service, "Harvest_Moon", october));
        assertNull(CalendarFactors.live(service, "No_Such_Event", october), "cannot answer, so every gate on it stays shut");
        assertNull(CalendarFactors.live(service, null, october));
        assertNull(CalendarFactors.live(service, "  ", october));
    }

    @Test
    void aSwitchedOffEventReadsNothingBecauseOffMeansAbsent() {
        long october = at("2026-10-02T12:00:00Z");
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertNull(CalendarFactors.live(service, "Hallows_Eve", october),
                "absent, never between seasons: every gate on it stays shut and a reader lists nothing");
        CalendarEventConfig.getInstance().setGlobalEnabled(true);
        CalendarFixtures.loadEvents(Map.of("hallows_eve", CalendarFixtures.event("Hallows_Eve",
                "{ \"Enabled\": false, \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" }, \"FirstYear\": 2026 }")));
        assertNull(CalendarFactors.live(service, "Hallows_Eve", october), "its own switch, the same");
        CalendarFixtures.loadDesignEvents();
        CalendarForces.getInstance().force("Hallows_Eve", false);
        assertEquals(0.0, CalendarFactors.live(service, "Hallows_Eve", october),
                "stopped by a command is not switched off");
    }

    @Test
    void yearReadsTheRunningRunsYearAndNothingOtherwise() {
        assertEquals(2026.0, CalendarFactors.year(service, "Hallows_Eve", at("2026-10-02T12:00:00Z")));
        assertNull(CalendarFactors.year(service, "Hallows_Eve", at("2026-12-01T00:00:00Z")));
        assertNull(CalendarFactors.year(service, null, 0L));
    }

    @Test
    void contributingClaimsBothIdsForEveryVocabulary() {
        CalendarFactors.contribute(service, () -> at("2026-10-02T12:00:00Z"));
        assertTrue(FactorContributions.isContributed(CalendarFactors.LIVE));
        assertTrue(FactorContributions.isContributed(CalendarFactors.YEAR));
        assertEquals(1.0, FactorContributions.provider(CalendarFactors.LIVE)
                .resolve(FactorContext.builder().param("Hallows_Eve").build()));
    }

    @Test
    void eachReadingShipsANamingOverlayWhoseKeyIsInTheEnglishFile() throws Exception {
        Set<String> english = CalendarFixtures.englishKeys(LANG);
        assertOverlay("Calendar_Live", CalendarFactors.LIVE, english);
        assertOverlay("Calendar_Year", CalendarFactors.YEAR, english);
        assertOverlay("Calendar_Feature", "ziggfreedcommon:feature", english);
    }

    private static void assertOverlay(String file, String factorId, Set<String> english) throws Exception {
        String path = "/Server/ZiggfreedCommon/Factors/" + file + ".json";
        String json;
        try (InputStream in = CalendarFactorsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped overlay: " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        DerivedFactorAsset asset = DerivedFactorAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, file, null)));
        assertTrue(asset.isOverlay(), path + " targets its factor through the Factor leaf");
        assertEquals(factorId, asset.namedFactorId());
        assertTrue(asset.carriesNaming(), path + " carries a name");
        String titleKey = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("Text")
                .get("TitleKey").getAsString();
        assertTrue(titleKey.startsWith(KEY_PREFIX), path + " names a key in this module's own lang file");
        assertTrue(english.contains(titleKey.substring(KEY_PREFIX.length())), "en-US " + LANG + " ships " + titleKey);
    }
}
