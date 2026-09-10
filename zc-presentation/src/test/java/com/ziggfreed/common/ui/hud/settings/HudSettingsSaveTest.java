package com.ziggfreed.common.ui.hud.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.ui.hud.panel.HudOwnerLayers;
import com.ziggfreed.common.ui.hud.panel.HudPanelAsset;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.ui.hud.panel.HudPanelOwnerWriter;

/**
 * A Save round trip, the way the page makes it: the drafted leaves go through the one writer into
 * the owner file as the nested groups an owner would type, come back through the same codec the
 * file is always read through, and show in the fields again; blanking them all takes every leaf
 * out again; and a hand-written entry keeps its other leaves, its comment and its own spelling.
 */
class HudSettingsSaveTest {

    private static final String PANEL = "World_Bars";

    @TempDir
    Path dir;

    @BeforeEach
    void pointTheOwnerFileHere() {
        HudOwnerLayers.setDirectory(dir);
    }

    @AfterEach
    void restore() {
        HudOwnerLayers.setDirectory(HudOwnerLayers.DEFAULT_DIRECTORY);
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private JsonObject entry(String key) throws Exception {
        String json = Files.readString(HudOwnerLayers.panelsFile(), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject(key);
    }

    @Test
    void aSaveRoundTripsEveryLeafIntoTheOwnerFileAndBackThroughTheFold() throws Exception {
        Map<String, String> typed = HudServerLeafTest.typed();
        HudServerLeaf.Draft drafted = HudServerLeaf.draft(PANEL, typed);
        assertTrue(drafted.accepted());

        assertTrue(HudPanelOwnerWriter.setLeaves(PANEL, drafted.leaves()));

        JsonObject grid = entry(PANEL);
        assertEquals(16, grid.getAsJsonObject("Position").get("OffsetX").getAsInt());
        assertEquals(-4, grid.getAsJsonObject("Position").get("OffsetY").getAsInt());
        assertEquals(3, grid.get("Columns").getAsInt());
        assertEquals(1, grid.get("RowsPerColumn").getAsInt());
        assertEquals(3, grid.getAsJsonObject("Gap").get("AfterRow").getAsInt(), "a nested group, as an owner types it");
        assertEquals(66, grid.getAsJsonObject("Gap").get("Pixels").getAsInt());
        assertEquals(3, grid.getAsJsonObject("Cutout").get("Column").getAsInt());
        assertEquals(2, grid.getAsJsonObject("Cutout").get("Rows").getAsInt());
        assertEquals(124, grid.get("MinHeight").getAsInt());
        assertEquals("#aabbccdd", grid.get("Color").getAsString(), "normalised: lower case, its hash");
        assertFalse(grid.has("Gap.AfterRow"), "never a dotted key");
        assertFalse(grid.has("Placement"), "a leaf the draft did not carry is not invented");

        HudPanelAsset panel = HudPanelConfig.getInstance().panel(PANEL);
        assertEquals(3, panel.authoredGap().afterRow());
        assertEquals(66, panel.authoredGap().pixels());
        assertEquals(3, panel.authoredCutout().column());
        assertEquals(2, panel.authoredCutout().rows());
        assertEquals(124, panel.authoredMinHeight());
        assertEquals("#aabbccdd", panel.authoredColor());
        for (HudServerLeaf leaf : HudServerLeaf.values()) {
            String shown = leaf.shown(panel);
            assertEquals(String.valueOf(leaf.value(typed.get(leaf.rowId(PANEL)))), shown,
                    leaf.name() + " shows again what was typed, as the codec reads it");
        }

        Map<String, String> blank = new LinkedHashMap<>();
        assertTrue(HudPanelOwnerWriter.setLeaves(PANEL, HudServerLeaf.draft(PANEL, blank).leaves()));

        grid = entry(PANEL);
        for (String gone : new String[] {"Columns", "RowsPerColumn", "MinHeight", "Color"}) {
            assertFalse(grid.has(gone), gone + " is removed by a blank");
        }
        assertFalse(grid.getAsJsonObject("Gap").has("AfterRow"));
        assertFalse(grid.getAsJsonObject("Gap").has("Pixels"));
        assertFalse(grid.getAsJsonObject("Cutout").has("Column"));
        assertFalse(grid.getAsJsonObject("Cutout").has("Rows"));
        panel = HudPanelConfig.getInstance().panel(PANEL);
        assertNull(panel.authoredGap(), "an emptied band group states nothing");
        assertNull(panel.authoredCutout());
        assertNull(panel.authoredMinHeight());
        assertNull(panel.authoredColor());
        assertNull(panel.authoredColumns());
    }

    @Test
    void aSaveOverAHandWrittenEntryKeepsItsOtherLeavesItsCommentAndItsSpelling() throws Exception {
        Files.writeString(HudOwnerLayers.panelsFile(), "{ \"$Comment\": \"mine\", \"world_bars\": { \"MaxVisible\": 4,"
                + " \"Gap\": { \"AfterRow\": 2, \"Pixels\": 30 } } }", StandardCharsets.UTF_8);
        Map<String, String> draft = new LinkedHashMap<>();
        draft.put("gappx:" + PANEL, "0");

        assertTrue(HudPanelOwnerWriter.setLeaves(PANEL, HudServerLeaf.draft(PANEL, draft).leaves()));

        JsonObject root = JsonParser.parseString(
                Files.readString(HudOwnerLayers.panelsFile(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("mine", root.get("$Comment").getAsString(), "the file-level comment survives");
        assertTrue(root.has("world_bars"), "the owner's own spelling of the key is kept");
        assertFalse(root.has("World_Bars"), "and no second entry folds onto the same id");
        JsonObject grid = root.getAsJsonObject("world_bars");
        assertEquals(4, grid.get("MaxVisible").getAsInt(), "a leaf the page never offers is untouched");
        assertEquals(0, grid.getAsJsonObject("Gap").get("Pixels").getAsInt(), "the band switched off, as typed");
        assertFalse(grid.getAsJsonObject("Gap").has("AfterRow"), "the blank field took its leaf out");

        HudPanelAsset panel = HudPanelConfig.getInstance().panel(PANEL);
        assertEquals(0, panel.authoredGap().pixels());
        assertFalse(panel.authoredGap().applies());
        assertEquals("0", HudServerLeaf.GAP_PIXELS.shown(panel), "and the field shows the zero it wrote");
    }
}
