package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * The panel file: its default position is the left column under the other left-column overlays, an
 * authored {@code Position} reads leaf by leaf over that default, a preset nothing recognises falls
 * back whole, {@code MaxVisible} is held to the slots the document declares, and the fold always
 * answers a panel.
 */
class HudBarPanelAssetTest {

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
    void theDefaultPositionIsTopLeftUnderTheInspectorCard() {
        HudPosition position = HudBarPanelAsset.defaults().position();

        assertSame(HudBarPanelAsset.DEFAULT_POSITION, position);
        assertEquals(HudPosition.AnchorEdge.TOP, position.getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.LEFT, position.getHorizontalEdge());
        assertEquals(16, position.getOffsetX());
        assertEquals(216, position.getOffsetY());
        assertTrue(HudBarPanelAsset.defaults().enabled());
        assertEquals(HudBarPanelAsset.MAX_SLOTS, HudBarPanelAsset.defaults().maxVisible());
    }

    @Test
    void anAuthoredPositionReadsLeafByLeafOverTheDefault() throws Exception {
        HudPosition moved = panel("{ \"Position\": { \"Preset\": \"BottomLeft\", \"OffsetY\": 220 } }",
                "default", null, null).position();

        assertEquals(HudPosition.AnchorEdge.BOTTOM, moved.getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.LEFT, moved.getHorizontalEdge());
        assertEquals(220, moved.getOffsetY(), "the restated offset");
        assertEquals(HudBarPanelAsset.DEFAULT_POSITION.getOffsetX(), moved.getOffsetX(),
                "an unauthored offset keeps the default's");
    }

    @Test
    void offsetsAloneKeepTheDefaultPreset() throws Exception {
        HudPosition nudged = panel("{ \"Position\": { \"OffsetX\": 40 } }", "default", null, null).position();

        assertEquals(HudPosition.AnchorEdge.TOP, nudged.getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.LEFT, nudged.getHorizontalEdge());
        assertEquals(40, nudged.getOffsetX());
        assertEquals(HudBarPanelAsset.DEFAULT_POSITION.getOffsetY(), nudged.getOffsetY());
    }

    @Test
    void anUnknownPresetFallsBackWhole() throws Exception {
        assertSame(HudBarPanelAsset.DEFAULT_POSITION,
                panel("{ \"Position\": { \"Preset\": \"Sideways\", \"OffsetY\": 9 } }", "default", null, null)
                        .position());
    }

    @Test
    void maxVisibleIsHeldToTheDocumentsSlots() throws Exception {
        assertEquals(2, panel("{ \"MaxVisible\": 2 }", "default", null, null).maxVisible());
        assertEquals(HudBarPanelAsset.MAX_SLOTS, panel("{ \"MaxVisible\": 40 }", "default", null, null).maxVisible(),
                "a document can only repaint slots it declares");
        assertEquals(1, panel("{ \"MaxVisible\": 0 }", "default", null, null).maxVisible(),
                "a panel that shows nothing is Enabled false, not MaxVisible 0");
    }

    @Test
    void aChildKeepsThePositionLeavesItDidNotRestate() throws Exception {
        HudBarPanelAsset base = panel("{ \"Position\": { \"Preset\": \"BottomLeft\", \"OffsetX\": 30, \"OffsetY\": 200 },"
                + " \"MaxVisible\": 3 }", "default", null, null);
        HudBarPanelAsset child = panel("{ \"Position\": { \"OffsetY\": 260 }, \"Enabled\": false }",
                "default", "default", base);

        assertFalse(child.enabled());
        assertEquals(3, child.maxVisible());
        HudPosition position = child.position();
        assertEquals(HudPosition.AnchorEdge.BOTTOM, position.getAnchorEdge(), "the preset is inherited");
        assertEquals(30, position.getOffsetX(), "the unrestated offset is inherited");
        assertEquals(260, position.getOffsetY(), "the restated offset is the child's");
    }

    @Test
    void theFoldAlwaysAnswersAPanel() throws Exception {
        assertTrue(HudBarPanelConfig.getInstance().current().enabled(),
                "before anything has loaded, the all-defaults panel answers");
        assertSame(HudBarPanelAsset.DEFAULT_POSITION, HudBarPanelConfig.getInstance().current().position());

        HudBarPanelAsset off = panel("{ \"Enabled\": false }", "Default", null, null);
        HudBarPanelConfig.getInstance().mergePackLayer(Map.of("Default", off));
        assertFalse(HudBarPanelConfig.getInstance().current().enabled(),
                "the folded Default panel is the one the bars are drawn on, whatever case it was keyed in");
    }
}
