package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * Where a panel ends up sitting: the placement file reads back as authored and says which panels
 * offer it; the fold lists what a picker offers in listing order; and the resolution reads the
 * player's own pick over the spot the panel names with the panel's inline leaves over that, over
 * the document's fallback, with every missing or unfit layer skipped rather than misapplied.
 */
class HudBarPlacementTest {

    private static final HudPosition FALLBACK =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 216);

    private static final HudBarLayout GRID = new HudBarLayout("grid", "test:grid", "Hud/Test.ui", "#Test",
            6, 3, 12, 200, 8, 130, FALLBACK);

    private static final HudBarLayout STACK = new HudBarLayout("default", "test:stack", "Hud/Test.ui", "#Test",
            2, 13, 12, 296, 12, 226, FALLBACK);

    private final HudBarPlacementConfig placements = HudBarPlacementConfig.getInstance();

    static HudBarPlacementAsset placement(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudBarPlacementAsset.class, id, null);
        return HudBarPlacementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    @BeforeEach
    void shipTheThreeSpots() throws Exception {
        placements.mergePackLayer(Map.of(
                "TopLeft", placement("{ \"Position\": { \"Preset\": \"TopLeft\", \"OffsetX\": 16, \"OffsetY\": 216 },"
                        + " \"Columns\": 2, \"RowsPerColumn\": 8, \"Panels\": [\"Default\", \"Grid\"], \"Order\": 10 }",
                        "TopLeft"),
                "TopRight", placement("{ \"Position\": { \"Preset\": \"TopRight\", \"OffsetX\": 24, \"OffsetY\": 10 },"
                        + " \"Columns\": 6, \"RowsPerColumn\": 1, \"Panels\": [\"Grid\"], \"Order\": 20 }", "TopRight"),
                "BottomLeft", placement("{ \"Position\": { \"Preset\": \"BottomLeft\", \"OffsetX\": 16, \"OffsetY\": 16 },"
                        + " \"Columns\": 2, \"RowsPerColumn\": 1, \"Panels\": [\"Grid\"], \"Order\": 30 }", "BottomLeft")));
        HudBarPlacement.resetWarnings();
    }

    @AfterEach
    void clearFolds() {
        placements.mergePackLayer(Map.of());
        placements.mergeOwnerLayer(Map.of());
        HudBarPanelConfig.getInstance().mergePackLayer(Map.of());
        HudBarPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    // ==================== the file ====================

    @Test
    void aPlacementReadsBackAsAuthoredAndFitsThePanelsItNames() throws Exception {
        HudBarPlacementAsset spot = placement("{ \"LabelKey\": \"x.y\", \"Position\": { \"Preset\": \"BottomCenter\","
                + " \"OffsetY\": 120 }, \"Columns\": 3, \"Panels\": [\" grid \"] }", "AboveHotbar");

        assertEquals("x.y", spot.labelKey());
        assertEquals("BottomCenter", spot.position().preset());
        assertEquals(120, spot.position().offsetY());
        assertNull(spot.position().offsetX(), "only the leaves the file states");
        assertEquals(3, spot.columns());
        assertNull(spot.rowsPerColumn(), "unauthored keeps the panel's own");
        assertTrue(spot.fits("Grid"), "named, whatever the case or the spacing");
        assertFalse(spot.fits("default"), "not named");
        assertTrue(spot.enabled());
        assertEquals(HudBarPlacementAsset.DEFAULT_ORDER, spot.order());
    }

    @Test
    void aPlacementNamingNoPanelFitsEveryPanel() throws Exception {
        HudBarPlacementAsset spot = placement("{ \"Position\": { \"Preset\": \"Center\" } }", "Middle");

        assertTrue(spot.fits("Grid"));
        assertTrue(spot.fits("default"));
        assertFalse(spot.fits(null), "no panel at all is not a panel it fits");
    }

    @Test
    void thePickerListsWhatFitsInListingOrderAndSkipsWhatIsOff() throws Exception {
        placements.mergeOwnerLayer(Map.of(
                "TopRight", placement("{ \"Enabled\": false }", "TopRight"),
                "Anywhere", placement("{ \"Order\": 5 }", "Anywhere")));

        List<String> forGrid = placements.offeredFor("grid").stream().map(HudBarPlacementAsset::getId).toList();
        List<String> forStack = placements.offeredFor("default").stream().map(HudBarPlacementAsset::getId).toList();

        assertEquals(List.of("Anywhere", "TopLeft", "BottomLeft"), forGrid,
                "by Order then id; TopRight is switched off in the owner layer");
        assertEquals(List.of("Anywhere", "TopLeft"), forStack, "the two bottom spots were measured for the grid alone");
    }

    // ==================== the resolution ====================

    @Test
    void nothingAuthoredSitsWhereTheDocumentSays() {
        HudBarPlacement resolved = HudBarPlacement.resolve(HudBarPanelAsset.defaults(), GRID, null, placements);

        assertSame(FALLBACK, resolved.position());
        assertEquals(HudBarPlacement.DEFAULT_COLUMNS, resolved.columns());
        assertEquals(HudBarPlacement.DEFAULT_ROWS_PER_COLUMN, resolved.rowsPerColumn());
        assertNull(resolved.id());
    }

    @Test
    void thePanelsSpotSuppliesTheCornerAndTheSpread() throws Exception {
        HudBarPanelAsset panel = HudBarPanelAssetTest.panel("{ \"Placement\": \"bottomleft\" }", "grid", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(panel, GRID, null, placements);

        assertEquals(HudPosition.AnchorEdge.BOTTOM, resolved.position().getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.LEFT, resolved.position().getHorizontalEdge());
        assertEquals(16, resolved.position().getOffsetY());
        assertEquals(2, resolved.columns());
        assertEquals(1, resolved.rowsPerColumn());
        assertEquals("BottomLeft", resolved.id(), "the spot's id, in its own spelling");
        assertTrue(resolved.bottomUp());
        assertFalse(resolved.rightToLeft());
    }

    @Test
    void thePanelsInlineLeavesNudgeTheSpotLeafByLeaf() throws Exception {
        HudBarPanelAsset panel = HudBarPanelAssetTest.panel("{ \"Placement\": \"TopRight\","
                + " \"Position\": { \"OffsetY\": 40 }, \"Columns\": 3 }", "grid", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(panel, GRID, null, placements);

        assertEquals(HudPosition.HorizontalEdge.RIGHT, resolved.position().getHorizontalEdge(), "the spot's corner");
        assertEquals(24, resolved.position().getOffsetX(), "the spot's untouched offset");
        assertEquals(40, resolved.position().getOffsetY(), "the panel's restated offset");
        assertEquals(3, resolved.columns(), "the panel's restated spread");
        assertEquals(1, resolved.rowsPerColumn(), "the spot's untouched spread");
        assertEquals("TopRight", resolved.id());
        assertTrue(resolved.rightToLeft());
    }

    @Test
    void aPanelNamingNoSpotStillReadsItsInlineLeavesOverTheDocument() throws Exception {
        HudBarPanelAsset panel = HudBarPanelAssetTest.panel("{ \"Position\": { \"Preset\": \"BottomLeft\","
                + " \"OffsetY\": 220 }, \"RowsPerColumn\": 4 }", "default", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(panel, STACK, null, placements);

        assertEquals(HudPosition.AnchorEdge.BOTTOM, resolved.position().getAnchorEdge());
        assertEquals(16, resolved.position().getOffsetX(), "the document's untouched offset");
        assertEquals(220, resolved.position().getOffsetY());
        assertEquals(4, resolved.rowsPerColumn());
        assertNull(resolved.id(), "an inline-only panel resolved from no spot");
    }

    @Test
    void aPlayersPickReplacesTheWholeGroup() throws Exception {
        HudBarPanelAsset panel = HudBarPanelAssetTest.panel("{ \"Placement\": \"BottomLeft\","
                + " \"Position\": { \"OffsetY\": 90 }, \"Columns\": 1 }", "grid", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(panel, GRID, "topright", placements);

        assertEquals(HudPosition.HorizontalEdge.RIGHT, resolved.position().getHorizontalEdge());
        assertEquals(10, resolved.position().getOffsetY(),
                "the owner's nudge belonged to the owner's corner and does not follow the pick");
        assertEquals(6, resolved.columns(), "the pick's own spread, not the owner's inline one");
        assertEquals("TopRight", resolved.id());
    }

    @Test
    void aPickThatDoesNotFitIsOffOrIsGoneFallsBackToTheServersChoice() throws Exception {
        HudBarPanelAsset panel = HudBarPanelAssetTest.panel("{ \"Placement\": \"TopLeft\" }", "grid", null, null);
        placements.mergeOwnerLayer(Map.of("TopRight", placement("{ \"Enabled\": false }", "TopRight")));

        assertEquals("TopLeft", HudBarPlacement.resolve(panel, GRID, "topright", placements).id(),
                "a spot switched off is as good as gone");
        assertEquals("TopLeft", HudBarPlacement.resolve(panel, GRID, "nowhere", placements).id(),
                "a spot nothing authored");
        assertEquals("TopLeft", HudBarPlacement.resolve(panel, STACK, "bottomleft", placements).id(),
                "a spot measured for another panel");
    }

    @Test
    void aPanelNamingASpotNothingAuthoredSitsWhereTheDocumentSays() throws Exception {
        HudBarPanelAsset panel = HudBarPanelAssetTest.panel("{ \"Placement\": \"Elsewhere\","
                + " \"Position\": { \"OffsetX\": 50 } }", "grid", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(panel, GRID, null, placements);

        assertEquals(HudPosition.AnchorEdge.TOP, resolved.position().getAnchorEdge(), "the document's corner");
        assertEquals(50, resolved.position().getOffsetX(), "the inline leaf still applies over it");
        assertNull(resolved.id());
    }

    @Test
    void theColumnCapNeverExceedsTheDocument() {
        HudBarPlacement wide = new HudBarPlacement(FALLBACK, 9, 0, null);

        assertEquals(9, wide.columns());
        assertEquals(6, wide.columns(GRID.columns()), "the document declares six");
        assertEquals(1, wide.rowsPerColumn(), "a non-positive spread reads as one");
    }

    @Test
    void anUnknownPresetKeepsTheWholeOfTheLayerBelow() {
        HudBarPosition sideways = new HudBarPosition("Sideways", 5, 9);
        HudPosition kept = sideways.over(FALLBACK);

        assertSame(FALLBACK, kept, "offsets measured from a corner nobody recognised mean nothing");
        assertEquals(9, new HudBarPosition(null, null, 9).over(FALLBACK).getOffsetY(), "one leaf, one change");
        assertTrue(new HudBarPosition(" ", null, null).isEmpty());
    }
}
