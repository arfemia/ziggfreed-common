package com.ziggfreed.common.objectives.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.settings.PlayerSettingsAsset;
import com.ziggfreed.common.settings.PlayerSettingsConfig;
import com.ziggfreed.common.settings.page.SettingsOption;
import com.ziggfreed.common.settings.page.SettingsRow;
import com.ziggfreed.common.settings.page.SettingsSection;
import com.ziggfreed.common.settings.page.SettingsViewer;
import com.ziggfreed.common.settings.page.SpotOptions;
import com.ziggfreed.common.ui.hud.panel.HudSpotAsset;
import com.ziggfreed.common.ui.hud.panel.HudSpotConfig;

/**
 * The Quest tracker card: Show, then where it sits among the spots offered to the tracker alone; the
 * owner's switch hides the whole card and each lock hides its own row.
 */
class TrackerSettingsTest {

    @Nonnull
    private static HudSpotAsset spot(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudSpotAsset.class, id, null);
        return HudSpotAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    @Nonnull
    private static PlayerSettingsAsset record(@Nonnull String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(PlayerSettingsAsset.class, "Default", null);
        return PlayerSettingsAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    @BeforeEach
    void spots() throws Exception {
        HudSpotConfig.getInstance().mergePackLayer(Map.of(
                "Quest_Tracker_Right", spot("{ \"Panels\": [\"Quest_Tracker\"] }", "Quest_Tracker_Right"),
                "Top_Right", spot("{ \"Panels\": [\"World_Bars\"] }", "Top_Right"),
                "Open", spot("{ }", "Open")));
    }

    @AfterEach
    void clear() {
        TrackedQuestHuds.deps(null);
        HudSpotConfig.getInstance().mergePackLayer(Map.of());
        HudSpotConfig.getInstance().mergeOwnerLayer(Map.of());
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of());
        PlayerSettingsConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Nonnull
    private static List<String> shown() {
        return TrackerSettings.section().rows().stream()
                .filter(row -> row.visible().test(SettingsViewer.NOBODY)).map(SettingsRow::id).toList();
    }

    @Test
    void theTrackerCardIsShowThenWhere() {
        SettingsSection section = TrackerSettings.section();

        assertEquals(TrackerSettings.ID, section.id());
        assertEquals("ziggfreedcommon.progression.settings.tracker", section.heading().getMessageId());
        assertEquals(List.of(TrackerSettings.SHOW, TrackerSettings.WHERE),
                section.rows().stream().map(SettingsRow::id).toList());
        assertEquals(List.of(TrackerSettings.SHOW, TrackerSettings.WHERE), shown(), "nothing fixed: both show");
    }

    @Test
    void theOwnersSwitchHidesTheWholeCard() {
        TrackedQuestHuds.deps(() -> TrackedQuestHudDeps.builder().enabled(() -> false).build());

        assertTrue(shown().isEmpty());
    }

    @Test
    void locksHideTheirRows() throws Exception {
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of("Default",
                record("{ \"QuestTracker\": { \"Show\": { \"Locked\": true } } }")));
        assertEquals(List.of(TrackerSettings.WHERE), shown());

        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of("Default",
                record("{ \"QuestTracker\": { \"Spot\": { \"Locked\": true } } }")));
        assertEquals(List.of(TrackerSettings.SHOW), shown());
    }

    @Test
    void whereOffersOnlyTheTrackersOwnSpots() {
        SettingsRow where = TrackerSettings.section().rows().get(1);

        assertEquals(List.of(SpotOptions.SERVER_CHOICE, "quest_tracker_right"),
                where.choice().options(SettingsViewer.NOBODY).stream().map(SettingsOption::value).toList(),
                "never a bar spot, nor a spot that names nothing");
        HudSpotConfig.getInstance().mergePackLayer(Map.of());
        assertFalse(shown().contains(TrackerSettings.WHERE), "no spot to offer: no Where row");
    }
}
