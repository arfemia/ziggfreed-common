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
import com.ziggfreed.common.ui.hud.card.HudCardLook;

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
            6, 6, 12, 200, 8, 130, 12, 5, 18, 12, FALLBACK);

    private static final HudBarLayout STACK = new HudBarLayout("default", "test:stack", "Hud/Test.ui", "#Test",
            2, 13, 12, 296, 12, 226, 12, 5, 18, 12, FALLBACK);

    private final HudBarPlacementConfig placements = HudBarPlacementConfig.getInstance();

    static HudBarPlacementAsset placement(String json, String id) throws IOException {
        return placement(json, id, null);
    }

    /** A placement decoded OVER {@code parent}, the way an owner entry is decoded over the pack's file. */
    static HudBarPlacementAsset placement(String json, String id, HudBarPlacementAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudBarPlacementAsset.class, id,
                parent == null ? null : parent.getId());
        return HudBarPlacementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @BeforeEach
    void shipTheThreeSpots() throws Exception {
        placements.mergePackLayer(Map.of(
                "TopLeft", placement("{ \"Position\": { \"Preset\": \"TopLeft\", \"OffsetX\": 16, \"OffsetY\": 216 },"
                        + " \"Columns\": 2, \"RowsPerColumn\": 8, \"MinHeight\": 120,"
                        + " \"Panels\": [\"Default\", \"Grid\"], \"Order\": 10 }", "TopLeft"),
                "TopRight", placement("{ \"Position\": { \"Preset\": \"TopRight\", \"OffsetX\": 24, \"OffsetY\": 10 },"
                        + " \"Columns\": 6, \"RowsPerColumn\": 1, \"Panels\": [\"Grid\"], \"Order\": 20 }", "TopRight"),
                "BottomLeft", placement("{ \"Position\": { \"Preset\": \"BottomLeft\", \"OffsetX\": 16, \"OffsetY\": 16 },"
                        + " \"Columns\": 2, \"RowsPerColumn\": 1, \"Gap\": { \"AfterRow\": 3, \"Pixels\": 48 },"
                        + " \"Cutout\": { \"Column\": 2, \"Rows\": 2 }, \"Color\": \"#ffffffb8\","
                        + " \"Panels\": [\"Grid\"], \"Order\": 30 }", "BottomLeft")));
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
                + " \"OffsetY\": 120 }, \"Columns\": 3, \"Gap\": { \"AfterRow\": 2, \"Pixels\": 30 },"
                + " \"Cutout\": { \"Column\": 3, \"Rows\": 2 }, \"MinHeight\": 90, \"Color\": \" #AABBCC \","
                + " \"Panels\": [\" grid \"] }",
                "AboveHotbar");

        assertEquals("x.y", spot.labelKey());
        assertEquals("#aabbcc", spot.color(), "normalised: lower case, its hash, no space");
        assertEquals("BottomCenter", spot.position().preset());
        assertEquals(120, spot.position().offsetY());
        assertNull(spot.position().offsetX(), "only the leaves the file states");
        assertEquals(3, spot.columns());
        assertNull(spot.rowsPerColumn(), "unauthored keeps the panel's own");
        assertEquals(2, spot.gap().afterRow());
        assertEquals(30, spot.gap().pixels());
        assertTrue(spot.gap().applies());
        assertEquals(3, spot.cutout().column());
        assertEquals(2, spot.cutout().rows());
        assertTrue(spot.cutout().applies());
        assertEquals(90, spot.minHeight());
        assertTrue(spot.fits("Grid"), "named, whatever the case or the spacing");
        assertFalse(spot.fits("default"), "not named");
        assertTrue(spot.enabled());
        assertEquals(HudBarPlacementAsset.DEFAULT_ORDER, spot.order());
    }

    @Test
    void anEmptyBandAndAFloorOfNothingReadAsUnauthored() throws Exception {
        HudBarPlacementAsset bare = placement("{ \"Gap\": { }, \"Cutout\": { }, \"MinHeight\": 0 }", "Bare");
        assertNull(bare.gap(), "a band group with no leaves changes nothing");
        assertNull(bare.cutout(), "a cut group with no leaves changes nothing");
        assertNull(bare.minHeight(), "a floor of nothing is no floor");

        HudBarPlacementAsset off = placement("{ \"Gap\": { \"AfterRow\": 0, \"Pixels\": -5 },"
                + " \"Cutout\": { \"Column\": 0, \"Rows\": -1 }, \"MinHeight\": -1 }", "Off");
        assertNull(off.minHeight());
        assertFalse(off.gap().applies(), "a band with a zero in it is stated, and states no band");
        assertEquals(0, off.gap().afterRow());
        assertEquals(0, off.gap().pixels());
        assertFalse(off.cutout().applies(), "a cut with a zero in it is stated, and states no cut");
        assertEquals(0, off.cutout().column());
        assertEquals(0, off.cutout().rows());
    }

    @Test
    void anOwnerEntryRestatesOneLeafOfTheBandOverThePacksFile() throws Exception {
        // The owner file's entry decodes over the pack's file exactly as a Parent child does, and
        // the band group merges leaf by leaf, so restating Pixels alone as zero switches the band
        // off while keeping the row it followed for whoever switches it back on.
        HudBarPlacementAsset shipped = placements.placement("BottomLeft");
        HudBarPlacementAsset owner = placement("{ \"Gap\": { \"Pixels\": 0 } }", "bottomleft", shipped);

        assertEquals(3, owner.gap().afterRow(), "the unrestated leaf is the pack's");
        assertEquals(0, owner.gap().pixels(), "the restated leaf is the owner's");
        assertFalse(owner.gap().applies());
        assertEquals(2, owner.columns(), "every other leaf is inherited");
        assertEquals("BottomLeft", owner.position().preset());
        assertEquals(2, owner.cutout().column(), "the cut is inherited whole");
        assertEquals(2, owner.cutout().rows());

        HudBarPlacementAsset uncut = placement("{ \"Cutout\": { \"Rows\": 0 } }", "bottomleft", shipped);
        assertEquals(2, uncut.cutout().column(), "the cut's unrestated leaf is the pack's");
        assertEquals(0, uncut.cutout().rows(), "the restated leaf is the owner's");
        assertFalse(uncut.cutout().applies(), "which is how an owner uses every cell again");
        assertEquals(48, uncut.gap().pixels(), "the band is untouched");

        HudBarPlacementAsset taller = placement("{ \"MinHeight\": 200 }", "topleft", placements.placement("TopLeft"));
        assertEquals(200, taller.minHeight());
        assertEquals(8, taller.rowsPerColumn(), "the spread is inherited");

        HudBarPlacementAsset tinted = placement("{ \"Color\": \"#101010\" }", "bottomleft", shipped);
        assertEquals("#101010", tinted.color(), "the restated colour is the owner's");
        assertEquals(3, tinted.gap().afterRow(), "the band is untouched");
        assertEquals("#ffffffb8", placement("{ \"Order\": 5 }", "bottomleft", shipped).color(),
                "the colour is inherited whole when the owner restates something else");
    }

    @Test
    void aColourThatIsNotAHexIsIgnoredAndTheLayerBelowDecides() throws Exception {
        HudBarPlacementAsset bad = placement("{ \"Color\": \"#not-a-colour\" }", "Bad");
        assertNull(bad.color(), "ignored with one warning naming the file and the value");

        HudBarPlacementAsset overBad = placement("{ \"Color\": \"#zz\" }", "bottomleft", placements.placement("BottomLeft"));
        assertNull(overBad.color(), "an owner's malformed restatement is ignored too, rather than read as the pack's");
    }

    @Test
    void aCutFoldsLeafByLeaf() {
        HudBarCutout spot = new HudBarCutout(3, 3);

        HudBarCutout shallower = new HudBarCutout(null, 1).over(spot);
        assertEquals(3, shallower.column(), "the unrestated leaf is the layer below's");
        assertEquals(1, shallower.rows(), "the restated leaf is this layer's");
        assertTrue(shallower.applies());

        HudBarCutout alone = new HudBarCutout(null, 1).over(null);
        assertEquals(0, alone.column(), "nothing below: the leaf stays unauthored");
        assertFalse(alone.applies(), "half a cut is no cut");
        assertTrue(new HudBarCutout(null, null).isEmpty());
        assertFalse(new HudBarCutout(0, null).isEmpty(), "a stated zero is a statement, not an absence");
    }

    @Test
    void aBandFoldsLeafByLeaf() {
        HudBarGap spot = new HudBarGap(3, 48);

        HudBarGap pixels = new HudBarGap(null, 10).over(spot);
        assertEquals(3, pixels.afterRow(), "the unrestated leaf is the layer below's");
        assertEquals(10, pixels.pixels(), "the restated leaf is this layer's");
        assertTrue(pixels.applies());

        HudBarGap alone = new HudBarGap(null, 10).over(null);
        assertEquals(0, alone.afterRow(), "nothing below: the leaf stays unauthored");
        assertFalse(alone.applies(), "half a band is no band");
        assertTrue(new HudBarGap(null, null).isEmpty());
        assertFalse(new HudBarGap(0, null).isEmpty(), "a stated zero is a statement, not an absence");
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
        assertNull(resolved.gap(), "the document leaves no band");
        assertNull(resolved.cutout(), "and no cut");
        assertEquals(0, resolved.minHeight(), "and has no floor");
        assertNull(resolved.color(), "and no colour of its own: the shared card look");
        assertNull(resolved.id());
    }

    @Test
    void theColourFoldsThroughEveryLayerInTurn() throws Exception {
        String shared = "#445566";

        // The shared record alone: nothing here states a colour, so the paint reads the record.
        HudBarPlacement bare = HudBarPlacement.resolve(HudBarPanelAssetTest.panel("{ \"Placement\": \"TopRight\" }",
                "grid", null, null), GRID, null, placements);
        assertNull(bare.color(), "a spot with no colour states none");
        assertEquals(shared, HudCardLook.resolve(bare.color(), shared).cardColor(), "so the shared look stands");

        // The spot's own colour, over the shared record.
        HudBarPanelAsset onSpot = HudBarPanelAssetTest.panel("{ \"Placement\": \"BottomLeft\" }", "grid", null, null);
        HudBarPlacement spot = HudBarPlacement.resolve(onSpot, GRID, null, placements);
        assertEquals("#ffffffb8", spot.color(), "the spot's");
        assertEquals("#ffffffb8", HudCardLook.resolve(spot.color(), shared).cardColor(), "over the shared record");

        // The panel's inline colour, over the spot's.
        HudBarPanelAsset inline = HudBarPanelAssetTest.panel("{ \"Placement\": \"BottomLeft\","
                + " \"Color\": \"#112233\" }", "grid", null, null);
        HudBarPlacement panel = HudBarPlacement.resolve(inline, GRID, null, placements);
        assertEquals("#112233", panel.color(), "the panel's, over the spot's");
        assertEquals("#112233", HudCardLook.resolve(panel.color(), shared).cardColor());

        // A player's pick swaps the whole group: the pick's own spot's colour, and neither the
        // named spot's nor the panel's inline one follows.
        HudBarPlacement pickedPlain = HudBarPlacement.resolve(inline, GRID, "topright", placements);
        assertNull(pickedPlain.color(), "the pick's spot states none, and the owner's inline colour does not follow");
        assertEquals(shared, HudCardLook.resolve(pickedPlain.color(), shared).cardColor(), "back to the shared look");
        HudBarPlacement pickedTinted = HudBarPlacement.resolve(inline, GRID, "bottomleft", placements);
        assertEquals("#ffffffb8", pickedTinted.color(), "the pick's own spot's colour, whole");

        // A panel naming no spot still reads its inline colour over the document.
        HudBarPlacement inlineOnly = HudBarPlacement.resolve(HudBarPanelAssetTest.panel("{ \"Color\": \"#6fbf73\" }",
                "default", null, null), STACK, null, placements);
        assertEquals("#6fbf73", inlineOnly.color());
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
        assertEquals(3, resolved.gap().afterRow(), "the spot's band");
        assertEquals(48, resolved.gap().pixels());
        assertTrue(resolved.gap().applies());
        assertEquals(2, resolved.cutout().column(), "the spot's cut");
        assertEquals(2, resolved.cutout().rows());
        assertTrue(resolved.cutout().applies());
        assertEquals(0, resolved.minHeight(), "a spot stating no floor has none");
        assertEquals("BottomLeft", resolved.id(), "the spot's id, in its own spelling");
        assertTrue(resolved.bottomUp());
        assertFalse(resolved.rightToLeft());
    }

    @Test
    void thePanelsInlineCutFoldsOverTheSpotLeafByLeaf() throws Exception {
        HudBarPanelAsset off = HudBarPanelAssetTest.panel("{ \"Placement\": \"BottomLeft\","
                + " \"Cutout\": { \"Rows\": 0 } }", "grid", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(off, GRID, null, placements);

        assertEquals(2, resolved.cutout().column(), "the spot's untouched leaf");
        assertEquals(0, resolved.cutout().rows(), "the panel's restated leaf");
        assertFalse(resolved.cutout().applies(), "which is how an owner uses every cell in place");
        assertEquals(3, resolved.gap().afterRow(), "the band is untouched");

        HudBarPanelAsset moved = HudBarPanelAssetTest.panel("{ \"Placement\": \"BottomLeft\","
                + " \"Cutout\": { \"Column\": 1 } }", "grid", null, null);
        HudBarPlacement other = HudBarPlacement.resolve(moved, GRID, null, placements);
        assertEquals(1, other.cutout().column());
        assertEquals(2, other.cutout().rows(), "the spot's untouched leaf");
        assertTrue(other.cutout().applies());
    }

    @Test
    void thePanelsInlineBandAndFloorFoldOverTheSpotLeafByLeaf() throws Exception {
        HudBarPanelAsset off = HudBarPanelAssetTest.panel("{ \"Placement\": \"BottomLeft\","
                + " \"Gap\": { \"Pixels\": 0 }, \"MinHeight\": 80 }", "grid", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(off, GRID, null, placements);

        assertEquals(3, resolved.gap().afterRow(), "the spot's untouched leaf");
        assertEquals(0, resolved.gap().pixels(), "the panel's restated leaf");
        assertFalse(resolved.gap().applies(), "which is how an owner switches the band off in place");
        assertEquals(80, resolved.minHeight(), "the panel's floor over a spot with none");

        HudBarPanelAsset moved = HudBarPanelAssetTest.panel("{ \"Placement\": \"BottomLeft\","
                + " \"Gap\": { \"AfterRow\": 2 } }", "grid", null, null);
        HudBarPlacement lower = HudBarPlacement.resolve(moved, GRID, null, placements);
        assertEquals(2, lower.gap().afterRow());
        assertEquals(48, lower.gap().pixels(), "the spot's untouched leaf");
        assertTrue(lower.gap().applies());

        HudBarPanelAsset shorter = HudBarPanelAssetTest.panel("{ \"Placement\": \"TopLeft\", \"MinHeight\": 40 }",
                "default", null, null);
        assertEquals(40, HudBarPlacement.resolve(shorter, STACK, null, placements).minHeight(),
                "the panel's floor over the spot's");
        assertEquals(120, HudBarPlacement.resolve(HudBarPanelAssetTest.panel("{ \"Placement\": \"TopLeft\" }",
                "default", null, null), STACK, null, placements).minHeight(), "the spot's floor when the panel restates none");
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
                + " \"OffsetY\": 220 }, \"RowsPerColumn\": 4, \"Gap\": { \"AfterRow\": 1, \"Pixels\": 20 },"
                + " \"Cutout\": { \"Column\": 1, \"Rows\": 1 }, \"MinHeight\": 50 }", "default", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(panel, STACK, null, placements);

        assertEquals(HudPosition.AnchorEdge.BOTTOM, resolved.position().getAnchorEdge());
        assertEquals(16, resolved.position().getOffsetX(), "the document's untouched offset");
        assertEquals(220, resolved.position().getOffsetY());
        assertEquals(4, resolved.rowsPerColumn());
        assertEquals(1, resolved.gap().afterRow(), "a band stated inline over a document that leaves none");
        assertEquals(20, resolved.gap().pixels());
        assertEquals(1, resolved.cutout().column(), "a cut stated inline over a document that leaves none");
        assertEquals(1, resolved.cutout().rows());
        assertEquals(50, resolved.minHeight());
        assertNull(resolved.id(), "an inline-only panel resolved from no spot");
    }

    @Test
    void aPlayersPickReplacesTheWholeGroup() throws Exception {
        HudBarPanelAsset panel = HudBarPanelAssetTest.panel("{ \"Placement\": \"BottomLeft\","
                + " \"Position\": { \"OffsetY\": 90 }, \"Columns\": 1, \"Gap\": { \"AfterRow\": 2 },"
                + " \"Cutout\": { \"Rows\": 1 }, \"MinHeight\": 70 }", "grid", null, null);

        HudBarPlacement resolved = HudBarPlacement.resolve(panel, GRID, "topright", placements);

        assertEquals(HudPosition.HorizontalEdge.RIGHT, resolved.position().getHorizontalEdge());
        assertEquals(10, resolved.position().getOffsetY(),
                "the owner's nudge belonged to the owner's corner and does not follow the pick");
        assertEquals(6, resolved.columns(), "the pick's own spread, not the owner's inline one");
        assertNull(resolved.gap(), "the pick leaves no band, and the owner's inline one does not follow");
        assertNull(resolved.cutout(), "the pick leaves no cut, and the owner's inline one does not follow");
        assertEquals(0, resolved.minHeight(), "nor the owner's floor");
        assertEquals("TopRight", resolved.id());

        HudBarPlacement picked = HudBarPlacement.resolve(panel, GRID, "bottomleft", placements);
        assertEquals(3, picked.gap().afterRow(), "a pick carries its own spot's band whole");
        assertEquals(48, picked.gap().pixels());
        assertEquals(2, picked.cutout().column(), "and its own spot's cut whole");
        assertEquals(2, picked.cutout().rows(), "the owner's inline Rows does not follow");
        assertEquals(0, picked.minHeight());
        assertEquals(120, HudBarPlacement.resolve(panel, GRID, "topleft", placements).minHeight(),
                "and its own spot's floor");
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
        HudBarPlacement wide = new HudBarPlacement(FALLBACK, 9, 0, null, null, -4, null, null);

        assertEquals(9, wide.columns());
        assertEquals(6, wide.columns(GRID.columns()), "the document declares six");
        assertEquals(1, wide.rowsPerColumn(), "a non-positive spread reads as one");
        assertEquals(0, wide.minHeight(), "a floor below nothing is no floor");
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
