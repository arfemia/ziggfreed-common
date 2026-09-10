package com.ziggfreed.common.ui.hud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The panel file: every leaf is optional and reads as "the layer below decides", the spot it names
 * and its inline leaves are read back as authored, a child keeps the leaves it did not restate,
 * {@code MaxVisible} is held to the slots the document declares, and the fold always answers.
 * Where the panel ends up SITTING is {@link HudSpotTest}'s.
 */
class HudPanelAssetTest {

    /** How many slots the panel under test declares; the ceiling is the drawing document's, not the file's. */
    private static final int SLOTS = 4;

    static HudPanelAsset panel(String json, String id, String parentId, HudPanelAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudPanelAsset.class, id, parentId);
        return HudPanelAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @AfterEach
    void clearFold() {
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    void anUnauthoredPanelIsOnNamesNoSpotAndShowsEverySlot() {
        HudPanelAsset panel = HudPanelAsset.defaults();

        assertTrue(panel.enabled());
        assertNull(panel.placement(), "no spot named: the document decides");
        assertNull(panel.authoredPosition());
        assertNull(panel.authoredColumns());
        assertNull(panel.authoredRowsPerColumn());
        assertNull(panel.authoredGap(), "no band restated: the spot's stands");
        assertNull(panel.authoredCutout(), "no cut restated: the spot's stands");
        assertNull(panel.authoredMinHeight(), "no floor restated: the spot's stands");
        assertNull(panel.authoredColor(), "no colour restated: the spot's, else the shared card look, stands");
        assertNull(panel.labelKey(), "unnamed: listed by its id");
        assertEquals(SLOTS, panel.maxVisible(SLOTS));
        assertEquals(HudPanelAsset.DEFAULT_REPAINT_MS, panel.repaintMs());
    }

    @Test
    void theSpotAndTheInlineLeavesReadBackAsAuthored() throws Exception {
        HudPanelAsset panel = panel("{ \"Placement\": \" Bottom_Left \", \"LabelKey\": \"my.panel\","
                + " \"Position\": { \"OffsetY\": 220 }, \"Columns\": 3, \"RowsPerColumn\": 2,"
                + " \"Gap\": { \"AfterRow\": 2, \"Pixels\": 40 }, \"Cutout\": { \"Column\": 3, \"Rows\": 1 },"
                + " \"MinHeight\": 90, \"Color\": \"#AABBCC80\" }",
                "Activity_Ledger", null, null);

        assertEquals("Bottom_Left", panel.placement(), "trimmed, case kept for the fold to fold");
        assertEquals("#aabbcc80", panel.authoredColor(), "normalised, its transparency kept");
        assertEquals("my.panel", panel.labelKey());
        assertNull(panel.authoredPosition().preset(), "only the offset was restated");
        assertEquals(220, panel.authoredPosition().offsetY());
        assertEquals(3, panel.authoredColumns());
        assertEquals(2, panel.authoredRowsPerColumn());
        assertEquals(2, panel.authoredGap().afterRow());
        assertEquals(40, panel.authoredGap().pixels());
        assertEquals(3, panel.authoredCutout().column());
        assertEquals(1, panel.authoredCutout().rows());
        assertEquals(90, panel.authoredMinHeight());
    }

    @Test
    void anEmptyPositionGroupAndNonPositiveSpreadsReadAsUnauthored() throws Exception {
        HudPanelAsset panel = panel("{ \"Position\": { }, \"Columns\": 0, \"RowsPerColumn\": -2,"
                + " \"Gap\": { }, \"Cutout\": { }, \"MinHeight\": 0 }", "Activity_Ledger", null, null);

        assertNull(panel.authoredPosition(), "a group with no leaves changes nothing");
        assertNull(panel.authoredColumns());
        assertNull(panel.authoredRowsPerColumn());
        assertNull(panel.authoredGap(), "a band group with no leaves changes nothing");
        assertNull(panel.authoredCutout(), "a cut group with no leaves changes nothing");
        assertNull(panel.authoredMinHeight(), "a floor of nothing is no floor");
    }

    @Test
    void aColourThatIsNotAHexReadsAsUnauthored() throws Exception {
        assertNull(panel("{ \"Color\": \"#not-a-colour\" }", "Activity_Ledger", null, null).authoredColor(),
                "ignored with one warning, so the spot's colour or the shared look stands");
        assertNull(panel("{ \"Color\": \"  \" }", "Activity_Ledger", null, null).authoredColor(), "a blank is nothing stated");
    }

    @Test
    void maxVisibleIsHeldToTheDocumentsSlots() throws Exception {
        assertEquals(2, panel("{ \"MaxVisible\": 2 }", "Activity_Ledger", null, null).maxVisible(SLOTS));
        assertEquals(SLOTS, panel("{ \"MaxVisible\": 40 }", "Activity_Ledger", null, null).maxVisible(SLOTS),
                "a document can only repaint slots it declares");
        assertEquals(1, panel("{ \"MaxVisible\": 0 }", "Activity_Ledger", null, null).maxVisible(SLOTS),
                "a panel that shows nothing is Enabled false, not MaxVisible 0");
    }

    @Test
    void aChildKeepsTheLeavesItDidNotRestate() throws Exception {
        HudPanelAsset base = panel("{ \"Placement\": \"Top_Right\","
                + " \"Position\": { \"Preset\": \"BottomLeft\", \"OffsetX\": 30, \"OffsetY\": 200 },"
                + " \"Gap\": { \"AfterRow\": 2, \"Pixels\": 40 }, \"Cutout\": { \"Column\": 2, \"Rows\": 3 },"
                + " \"MinHeight\": 60, \"Color\": \"#112233\", \"MaxVisible\": 3 }", "Activity_Ledger", null, null);
        HudPanelAsset child = panel("{ \"Position\": { \"OffsetY\": 260 }, \"Gap\": { \"Pixels\": 10 },"
                + " \"Cutout\": { \"Rows\": 0 }, \"Enabled\": false }", "Activity_Ledger", "Activity_Ledger", base);

        assertFalse(child.enabled());
        assertEquals(3, child.maxVisible(SLOTS));
        assertEquals("Top_Right", child.placement(), "the spot is inherited");
        HudSpotPosition position = child.authoredPosition();
        assertEquals("BottomLeft", position.preset(), "the preset is inherited");
        assertEquals(30, position.offsetX(), "the unrestated offset is inherited");
        assertEquals(260, position.offsetY(), "the restated offset is the child's");
        assertEquals(2, child.authoredGap().afterRow(), "the band's unrestated leaf is inherited");
        assertEquals(10, child.authoredGap().pixels(), "the band's restated leaf is the child's");
        assertEquals(2, child.authoredCutout().column(), "the cut's unrestated leaf is inherited");
        assertEquals(0, child.authoredCutout().rows(), "the cut's restated leaf is the child's: switched off");
        assertFalse(child.authoredCutout().applies());
        assertEquals(60, child.authoredMinHeight(), "the floor is inherited");
        assertEquals("#112233", child.authoredColor(), "the colour is inherited");
    }

    @Test
    void theFoldAlwaysAnswersAPanel() throws Exception {
        assertTrue(HudPanelConfig.getInstance().ledger().enabled(),
                "before anything has loaded, the all-defaults panel answers");
        assertNull(HudPanelConfig.getInstance().world().placement());

        HudPanelAsset off = panel("{ \"Enabled\": false }", "activity_ledger", null, null);
        HudPanelConfig.getInstance().mergePackLayer(Map.of("activity_ledger", off));
        assertFalse(HudPanelConfig.getInstance().ledger().enabled(),
                "the folded Activity_Ledger panel is the one the ledger draws, whatever case it was keyed in");
    }
}
