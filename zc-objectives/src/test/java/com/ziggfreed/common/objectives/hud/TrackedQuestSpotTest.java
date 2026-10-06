package com.ziggfreed.common.objectives.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.panel.HudSpotAsset;
import com.ziggfreed.common.ui.hud.panel.HudSpotConfig;

/**
 * Where a player's tracker sits: their pick when it names a spot that is on and offered to the tracker,
 * folded leaf by leaf over the server's own position; otherwise the server's position, whatever happened
 * to the spot since they picked it.
 */
class TrackedQuestSpotTest {

    private static final HudPosition SERVER =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.RIGHT, 24, 120);

    @Nonnull
    private static HudSpotAsset spot(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudSpotAsset.class, id, null);
        return HudSpotAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    @BeforeEach
    void spots() throws Exception {
        HudSpotConfig.getInstance().mergePackLayer(Map.of(
                "Quest_Tracker_Left", spot("{ \"Position\": { \"Preset\": \"TopLeft\", \"OffsetX\": 16, \"OffsetY\": 260 },"
                        + " \"Panels\": [\"Quest_Tracker\"] }", "Quest_Tracker_Left"),
                "Nudge", spot("{ \"Position\": { \"OffsetY\": 300 }, \"Panels\": [\"Quest_Tracker\"] }", "Nudge"),
                "Off", spot("{ \"Position\": { \"Preset\": \"TopLeft\" }, \"Panels\": [\"Quest_Tracker\"],"
                        + " \"Enabled\": false }", "Off"),
                "Low", spot("{ \"Position\": { \"Preset\": \"BottomRight\" }, \"Panels\": [\"Quest_Tracker\"] }", "Low"),
                "Bars", spot("{ \"Position\": { \"Preset\": \"BottomLeft\" }, \"Panels\": [\"World_Bars\"] }", "Bars"),
                "Anywhere", spot("{ \"Position\": { \"Preset\": \"BottomLeft\" } }", "Anywhere")));
    }

    @AfterEach
    void clear() {
        HudSpotConfig.getInstance().mergePackLayer(Map.of());
        HudSpotConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private static void assertAt(@Nonnull HudPosition expected, @Nonnull HudPosition actual) {
        assertEquals(expected.getAnchorEdge(), actual.getAnchorEdge());
        assertEquals(expected.getHorizontalEdge(), actual.getHorizontalEdge());
        assertEquals(expected.getOffsetX(), actual.getOffsetX());
        assertEquals(expected.getOffsetY(), actual.getOffsetY());
    }

    @Test
    void noPickKeepsTheServersPosition() {
        assertAt(SERVER, TrackedQuestSpot.position(SERVER, null, HudSpotConfig.getInstance()));
    }

    @Test
    void aPickedTrackerSpotMovesIt() {
        assertAt(new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 260),
                TrackedQuestSpot.position(SERVER, "quest_tracker_left", HudSpotConfig.getInstance()));
    }

    @Test
    void aSpotStatesOnlyTheLeavesItWantsDifferent() {
        assertAt(new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.RIGHT, 24, 300),
                TrackedQuestSpot.position(SERVER, "nudge", HudSpotConfig.getInstance()));
    }

    @Test
    void aPickTheTrackerIsNotOfferedFallsBackToTheServer() {
        HudSpotConfig spots = HudSpotConfig.getInstance();
        assertAt(SERVER, TrackedQuestSpot.position(SERVER, "off", spots));
        assertAt(SERVER, TrackedQuestSpot.position(SERVER, "bars", spots));
        assertAt(SERVER, TrackedQuestSpot.position(SERVER, "anywhere", spots));
        assertAt(SERVER, TrackedQuestSpot.position(SERVER, "gone_since", spots));
    }

    @Test
    void aSpotThatWouldPinTheTrackerAnywhereButTheTopIsNotTaken() {
        assertAt(SERVER, TrackedQuestSpot.position(SERVER, "low", HudSpotConfig.getInstance()));
    }
}
