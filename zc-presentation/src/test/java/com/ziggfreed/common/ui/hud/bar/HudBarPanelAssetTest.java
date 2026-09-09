package com.ziggfreed.common.ui.hud.bar;

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
 * Where the panel ends up SITTING is {@link HudBarPlacementTest}'s.
 */
class HudBarPanelAssetTest {

    /** How many slots the panel under test declares; the ceiling is the drawing document's, not the file's. */
    private static final int SLOTS = 4;

    static HudBarPanelAsset panel(String json, String id, String parentId, HudBarPanelAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudBarPanelAsset.class, id, parentId);
        return HudBarPanelAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @AfterEach
    void clearFold() {
        HudBarPanelConfig.getInstance().mergePackLayer(Map.of());
        HudBarPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    void anUnauthoredPanelIsOnNamesNoSpotAndShowsEverySlot() {
        HudBarPanelAsset panel = HudBarPanelAsset.defaults();

        assertTrue(panel.enabled());
        assertNull(panel.placement(), "no spot named: the document decides");
        assertNull(panel.authoredPosition());
        assertNull(panel.authoredColumns());
        assertNull(panel.authoredRowsPerColumn());
        assertNull(panel.labelKey(), "unnamed: listed by its id");
        assertEquals(SLOTS, panel.maxVisible(SLOTS));
        assertEquals(HudBarPanelAsset.DEFAULT_REPAINT_MS, panel.repaintMs());
    }

    @Test
    void theSpotAndTheInlineLeavesReadBackAsAuthored() throws Exception {
        HudBarPanelAsset panel = panel("{ \"Placement\": \" BottomLeft \", \"LabelKey\": \"my.panel\","
                + " \"Position\": { \"OffsetY\": 220 }, \"Columns\": 3, \"RowsPerColumn\": 2 }",
                "default", null, null);

        assertEquals("BottomLeft", panel.placement(), "trimmed, case kept for the fold to fold");
        assertEquals("my.panel", panel.labelKey());
        assertNull(panel.authoredPosition().preset(), "only the offset was restated");
        assertEquals(220, panel.authoredPosition().offsetY());
        assertEquals(3, panel.authoredColumns());
        assertEquals(2, panel.authoredRowsPerColumn());
    }

    @Test
    void anEmptyPositionGroupAndNonPositiveSpreadsReadAsUnauthored() throws Exception {
        HudBarPanelAsset panel = panel("{ \"Position\": { }, \"Columns\": 0, \"RowsPerColumn\": -2 }",
                "default", null, null);

        assertNull(panel.authoredPosition(), "a group with no leaves changes nothing");
        assertNull(panel.authoredColumns());
        assertNull(panel.authoredRowsPerColumn());
    }

    @Test
    void maxVisibleIsHeldToTheDocumentsSlots() throws Exception {
        assertEquals(2, panel("{ \"MaxVisible\": 2 }", "default", null, null).maxVisible(SLOTS));
        assertEquals(SLOTS, panel("{ \"MaxVisible\": 40 }", "default", null, null).maxVisible(SLOTS),
                "a document can only repaint slots it declares");
        assertEquals(1, panel("{ \"MaxVisible\": 0 }", "default", null, null).maxVisible(SLOTS),
                "a panel that shows nothing is Enabled false, not MaxVisible 0");
    }

    @Test
    void aChildKeepsTheLeavesItDidNotRestate() throws Exception {
        HudBarPanelAsset base = panel("{ \"Placement\": \"TopRight\","
                + " \"Position\": { \"Preset\": \"BottomLeft\", \"OffsetX\": 30, \"OffsetY\": 200 },"
                + " \"MaxVisible\": 3 }", "default", null, null);
        HudBarPanelAsset child = panel("{ \"Position\": { \"OffsetY\": 260 }, \"Enabled\": false }",
                "default", "default", base);

        assertFalse(child.enabled());
        assertEquals(3, child.maxVisible(SLOTS));
        assertEquals("TopRight", child.placement(), "the spot is inherited");
        HudBarPosition position = child.authoredPosition();
        assertEquals("BottomLeft", position.preset(), "the preset is inherited");
        assertEquals(30, position.offsetX(), "the unrestated offset is inherited");
        assertEquals(260, position.offsetY(), "the restated offset is the child's");
    }

    @Test
    void theFoldAlwaysAnswersAPanel() throws Exception {
        assertTrue(HudBarPanelConfig.getInstance().current().enabled(),
                "before anything has loaded, the all-defaults panel answers");
        assertNull(HudBarPanelConfig.getInstance().grid().placement());

        HudBarPanelAsset off = panel("{ \"Enabled\": false }", "Default", null, null);
        HudBarPanelConfig.getInstance().mergePackLayer(Map.of("Default", off));
        assertFalse(HudBarPanelConfig.getInstance().current().enabled(),
                "the folded Default panel is the one the bars are drawn on, whatever case it was keyed in");
    }
}
