package com.ziggfreed.common.ui.hud.panel;

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
class HudSpotTest {

    private static final HudPosition FALLBACK =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 216);

    private static final HudPanelLayout GRID = new HudPanelLayout("World_Bars", "test:world", "Hud/Test.ui", "#Test",
            6, 6, 12, 200, 8, 130, 12, 5, 18, 12, FALLBACK);

    private static final HudPanelLayout STACK = new HudPanelLayout("Activity_Ledger", "test:ledger", "Hud/Test.ui", "#Test",
            2, 13, 12, 296, 12, 226, 12, 5, 18, 12, FALLBACK);

    private final HudSpotConfig spots = HudSpotConfig.getInstance();

    static HudSpotAsset spot(String json, String id) throws IOException {
        return spot(json, id, null);
    }

    /** A placement decoded OVER {@code parent}, the way an owner entry is decoded over the pack's file. */
    static HudSpotAsset spot(String json, String id, HudSpotAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudSpotAsset.class, id,
                parent == null ? null : parent.getId());
        return HudSpotAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @BeforeEach
    void shipTheThreeSpots() throws Exception {
        spots.mergePackLayer(Map.of(
                "Top_Left", spot("{ \"Position\": { \"Preset\": \"TopLeft\", \"OffsetX\": 16, \"OffsetY\": 216 },"
                        + " \"Columns\": 2, \"RowsPerColumn\": 8, \"MinHeight\": 120,"
                        + " \"Panels\": [\"Activity_Ledger\", \"World_Bars\"], \"Order\": 10 }", "Top_Left"),
                "Top_Right", spot("{ \"Position\": { \"Preset\": \"TopRight\", \"OffsetX\": 24, \"OffsetY\": 10 },"
                        + " \"Columns\": 6, \"RowsPerColumn\": 1, \"Panels\": [\"World_Bars\"], \"Order\": 20 }", "Top_Right"),
                "Bottom_Left", spot("{ \"Position\": { \"Preset\": \"BottomLeft\", \"OffsetX\": 16, \"OffsetY\": 16 },"
                        + " \"Columns\": 2, \"RowsPerColumn\": 1, \"Gap\": { \"AfterRow\": 3, \"Pixels\": 48 },"
                        + " \"Cutout\": { \"Column\": 2, \"Rows\": 2 }, \"Color\": \"#ffffffb8\","
                        + " \"Panels\": [\"World_Bars\"], \"Order\": 30 }", "Bottom_Left")));
        HudSpot.resetWarnings();
    }

    @AfterEach
    void clearFolds() {
        spots.mergePackLayer(Map.of());
        spots.mergeOwnerLayer(Map.of());
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    // ==================== the file ====================

    @Test
    void aPlacementReadsBackAsAuthoredAndFitsThePanelsItNames() throws Exception {
        HudSpotAsset spot = spot("{ \"LabelKey\": \"x.y\", \"Position\": { \"Preset\": \"BottomCenter\","
                + " \"OffsetY\": 120 }, \"Columns\": 3, \"Gap\": { \"AfterRow\": 2, \"Pixels\": 30 },"
                + " \"Cutout\": { \"Column\": 3, \"Rows\": 2 }, \"MinHeight\": 90, \"Color\": \" #AABBCC \","
                + " \"Panels\": [\" world_bars \"] }",
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
        assertTrue(spot.fits("World_Bars"), "named, whatever the case or the spacing");
        assertFalse(spot.fits("Activity_Ledger"), "not named");
        assertTrue(spot.enabled());
        assertEquals(HudSpotAsset.DEFAULT_ORDER, spot.order());
    }

    @Test
    void anEmptyBandAndAFloorOfNothingReadAsUnauthored() throws Exception {
        HudSpotAsset bare = spot("{ \"Gap\": { }, \"Cutout\": { }, \"MinHeight\": 0 }", "Bare");
        assertNull(bare.gap(), "a band group with no leaves changes nothing");
        assertNull(bare.cutout(), "a cut group with no leaves changes nothing");
        assertNull(bare.minHeight(), "a floor of nothing is no floor");

        HudSpotAsset off = spot("{ \"Gap\": { \"AfterRow\": 0, \"Pixels\": -5 },"
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
        HudSpotAsset shipped = spots.spot("Bottom_Left");
        HudSpotAsset owner = spot("{ \"Gap\": { \"Pixels\": 0 } }", "bottom_left", shipped);

        assertEquals(3, owner.gap().afterRow(), "the unrestated leaf is the pack's");
        assertEquals(0, owner.gap().pixels(), "the restated leaf is the owner's");
        assertFalse(owner.gap().applies());
        assertEquals(2, owner.columns(), "every other leaf is inherited");
        assertEquals("BottomLeft", owner.position().preset());
        assertEquals(2, owner.cutout().column(), "the cut is inherited whole");
        assertEquals(2, owner.cutout().rows());

        HudSpotAsset uncut = spot("{ \"Cutout\": { \"Rows\": 0 } }", "bottom_left", shipped);
        assertEquals(2, uncut.cutout().column(), "the cut's unrestated leaf is the pack's");
        assertEquals(0, uncut.cutout().rows(), "the restated leaf is the owner's");
        assertFalse(uncut.cutout().applies(), "which is how an owner uses every cell again");
        assertEquals(48, uncut.gap().pixels(), "the band is untouched");

        HudSpotAsset taller = spot("{ \"MinHeight\": 200 }", "top_left", spots.spot("Top_Left"));
        assertEquals(200, taller.minHeight());
        assertEquals(8, taller.rowsPerColumn(), "the spread is inherited");

        HudSpotAsset tinted = spot("{ \"Color\": \"#101010\" }", "bottom_left", shipped);
        assertEquals("#101010", tinted.color(), "the restated colour is the owner's");
        assertEquals(3, tinted.gap().afterRow(), "the band is untouched");
        assertEquals("#ffffffb8", spot("{ \"Order\": 5 }", "bottom_left", shipped).color(),
                "the colour is inherited whole when the owner restates something else");
    }

    @Test
    void aColourThatIsNotAHexIsIgnoredAndTheLayerBelowDecides() throws Exception {
        HudSpotAsset bad = spot("{ \"Color\": \"#not-a-colour\" }", "Bad");
        assertNull(bad.color(), "ignored with one warning naming the file and the value");

        HudSpotAsset overBad = spot("{ \"Color\": \"#zz\" }", "bottom_left", spots.spot("Bottom_Left"));
        assertNull(overBad.color(), "an owner's malformed restatement is ignored too, rather than read as the pack's");
    }

    @Test
    void aCutFoldsLeafByLeaf() {
        HudSpotCutout spot = new HudSpotCutout(3, 3);

        HudSpotCutout shallower = new HudSpotCutout(null, 1).over(spot);
        assertEquals(3, shallower.column(), "the unrestated leaf is the layer below's");
        assertEquals(1, shallower.rows(), "the restated leaf is this layer's");
        assertTrue(shallower.applies());

        HudSpotCutout alone = new HudSpotCutout(null, 1).over(null);
        assertEquals(0, alone.column(), "nothing below: the leaf stays unauthored");
        assertFalse(alone.applies(), "half a cut is no cut");
        assertTrue(new HudSpotCutout(null, null).isEmpty());
        assertFalse(new HudSpotCutout(0, null).isEmpty(), "a stated zero is a statement, not an absence");
    }

    @Test
    void aBandFoldsLeafByLeaf() {
        HudSpotGap spot = new HudSpotGap(3, 48);

        HudSpotGap pixels = new HudSpotGap(null, 10).over(spot);
        assertEquals(3, pixels.afterRow(), "the unrestated leaf is the layer below's");
        assertEquals(10, pixels.pixels(), "the restated leaf is this layer's");
        assertTrue(pixels.applies());

        HudSpotGap alone = new HudSpotGap(null, 10).over(null);
        assertEquals(0, alone.afterRow(), "nothing below: the leaf stays unauthored");
        assertFalse(alone.applies(), "half a band is no band");
        assertTrue(new HudSpotGap(null, null).isEmpty());
        assertFalse(new HudSpotGap(0, null).isEmpty(), "a stated zero is a statement, not an absence");
    }

    @Test
    void aPlacementNamingNoPanelFitsEveryPanel() throws Exception {
        HudSpotAsset spot = spot("{ \"Position\": { \"Preset\": \"Center\" } }", "Middle");

        assertTrue(spot.fits("World_Bars"));
        assertTrue(spot.fits("Activity_Ledger"));
        assertFalse(spot.fits(null), "no panel at all is not a panel it fits");
    }

    @Test
    void thePickerListsWhatFitsInListingOrderAndSkipsWhatIsOff() throws Exception {
        spots.mergeOwnerLayer(Map.of(
                "Top_Right", spot("{ \"Enabled\": false }", "Top_Right"),
                "Anywhere", spot("{ \"Order\": 5 }", "Anywhere")));

        List<String> forGrid = spots.offeredFor("World_Bars").stream().map(HudSpotAsset::getId).toList();
        List<String> forStack = spots.offeredFor("Activity_Ledger").stream().map(HudSpotAsset::getId).toList();

        assertEquals(List.of("Anywhere", "Top_Left", "Bottom_Left"), forGrid,
                "by Order then id; TopRight is switched off in the owner layer");
        assertEquals(List.of("Anywhere", "Top_Left"), forStack, "the two bottom spots were measured for the World bars alone");
    }

    // ==================== the resolution ====================

    @Test
    void nothingAuthoredSitsWhereTheDocumentSays() {
        HudSpot resolved = HudSpot.resolve(HudPanelAsset.defaults(), GRID, null, spots);

        assertSame(FALLBACK, resolved.position());
        assertEquals(HudSpot.DEFAULT_COLUMNS, resolved.columns());
        assertEquals(HudSpot.DEFAULT_ROWS_PER_COLUMN, resolved.rowsPerColumn());
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
        HudSpot bare = HudSpot.resolve(HudPanelAssetTest.panel("{ \"Placement\": \"Top_Right\" }",
                "World_Bars", null, null), GRID, null, spots);
        assertNull(bare.color(), "a spot with no colour states none");
        assertEquals(shared, HudCardLook.resolve(bare.color(), shared).cardColor(), "so the shared look stands");

        // The spot's own colour, over the shared record.
        HudPanelAsset onSpot = HudPanelAssetTest.panel("{ \"Placement\": \"Bottom_Left\" }", "World_Bars", null, null);
        HudSpot spot = HudSpot.resolve(onSpot, GRID, null, spots);
        assertEquals("#ffffffb8", spot.color(), "the spot's");
        assertEquals("#ffffffb8", HudCardLook.resolve(spot.color(), shared).cardColor(), "over the shared record");

        // The panel's inline colour, over the spot's.
        HudPanelAsset inline = HudPanelAssetTest.panel("{ \"Placement\": \"Bottom_Left\","
                + " \"Color\": \"#112233\" }", "World_Bars", null, null);
        HudSpot panel = HudSpot.resolve(inline, GRID, null, spots);
        assertEquals("#112233", panel.color(), "the panel's, over the spot's");
        assertEquals("#112233", HudCardLook.resolve(panel.color(), shared).cardColor());

        // A player's pick swaps the whole group: the pick's own spot's colour, and neither the
        // named spot's nor the panel's inline one follows.
        HudSpot pickedPlain = HudSpot.resolve(inline, GRID, "top_right", spots);
        assertNull(pickedPlain.color(), "the pick's spot states none, and the owner's inline colour does not follow");
        assertEquals(shared, HudCardLook.resolve(pickedPlain.color(), shared).cardColor(), "back to the shared look");
        HudSpot pickedTinted = HudSpot.resolve(inline, GRID, "bottom_left", spots);
        assertEquals("#ffffffb8", pickedTinted.color(), "the pick's own spot's colour, whole");

        // A panel naming no spot still reads its inline colour over the document.
        HudSpot inlineOnly = HudSpot.resolve(HudPanelAssetTest.panel("{ \"Color\": \"#6fbf73\" }",
                "Activity_Ledger", null, null), STACK, null, spots);
        assertEquals("#6fbf73", inlineOnly.color());
    }

    @Test
    void thePanelsSpotSuppliesTheCornerAndTheSpread() throws Exception {
        HudPanelAsset panel = HudPanelAssetTest.panel("{ \"Placement\": \"bottom_left\" }", "World_Bars", null, null);

        HudSpot resolved = HudSpot.resolve(panel, GRID, null, spots);

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
        assertEquals("Bottom_Left", resolved.id(), "the spot's id, in its own spelling");
        assertTrue(resolved.bottomUp());
        assertFalse(resolved.rightToLeft());
    }

    @Test
    void thePanelsInlineCutFoldsOverTheSpotLeafByLeaf() throws Exception {
        HudPanelAsset off = HudPanelAssetTest.panel("{ \"Placement\": \"Bottom_Left\","
                + " \"Cutout\": { \"Rows\": 0 } }", "World_Bars", null, null);

        HudSpot resolved = HudSpot.resolve(off, GRID, null, spots);

        assertEquals(2, resolved.cutout().column(), "the spot's untouched leaf");
        assertEquals(0, resolved.cutout().rows(), "the panel's restated leaf");
        assertFalse(resolved.cutout().applies(), "which is how an owner uses every cell in place");
        assertEquals(3, resolved.gap().afterRow(), "the band is untouched");

        HudPanelAsset moved = HudPanelAssetTest.panel("{ \"Placement\": \"Bottom_Left\","
                + " \"Cutout\": { \"Column\": 1 } }", "World_Bars", null, null);
        HudSpot other = HudSpot.resolve(moved, GRID, null, spots);
        assertEquals(1, other.cutout().column());
        assertEquals(2, other.cutout().rows(), "the spot's untouched leaf");
        assertTrue(other.cutout().applies());
    }

    @Test
    void thePanelsInlineBandAndFloorFoldOverTheSpotLeafByLeaf() throws Exception {
        HudPanelAsset off = HudPanelAssetTest.panel("{ \"Placement\": \"Bottom_Left\","
                + " \"Gap\": { \"Pixels\": 0 }, \"MinHeight\": 80 }", "World_Bars", null, null);

        HudSpot resolved = HudSpot.resolve(off, GRID, null, spots);

        assertEquals(3, resolved.gap().afterRow(), "the spot's untouched leaf");
        assertEquals(0, resolved.gap().pixels(), "the panel's restated leaf");
        assertFalse(resolved.gap().applies(), "which is how an owner switches the band off in place");
        assertEquals(80, resolved.minHeight(), "the panel's floor over a spot with none");

        HudPanelAsset moved = HudPanelAssetTest.panel("{ \"Placement\": \"Bottom_Left\","
                + " \"Gap\": { \"AfterRow\": 2 } }", "World_Bars", null, null);
        HudSpot lower = HudSpot.resolve(moved, GRID, null, spots);
        assertEquals(2, lower.gap().afterRow());
        assertEquals(48, lower.gap().pixels(), "the spot's untouched leaf");
        assertTrue(lower.gap().applies());

        HudPanelAsset shorter = HudPanelAssetTest.panel("{ \"Placement\": \"Top_Left\", \"MinHeight\": 40 }",
                "Activity_Ledger", null, null);
        assertEquals(40, HudSpot.resolve(shorter, STACK, null, spots).minHeight(),
                "the panel's floor over the spot's");
        assertEquals(120, HudSpot.resolve(HudPanelAssetTest.panel("{ \"Placement\": \"Top_Left\" }",
                "Activity_Ledger", null, null), STACK, null, spots).minHeight(), "the spot's floor when the panel restates none");
    }

    @Test
    void thePanelsInlineLeavesNudgeTheSpotLeafByLeaf() throws Exception {
        HudPanelAsset panel = HudPanelAssetTest.panel("{ \"Placement\": \"Top_Right\","
                + " \"Position\": { \"OffsetY\": 40 }, \"Columns\": 3 }", "World_Bars", null, null);

        HudSpot resolved = HudSpot.resolve(panel, GRID, null, spots);

        assertEquals(HudPosition.HorizontalEdge.RIGHT, resolved.position().getHorizontalEdge(), "the spot's corner");
        assertEquals(24, resolved.position().getOffsetX(), "the spot's untouched offset");
        assertEquals(40, resolved.position().getOffsetY(), "the panel's restated offset");
        assertEquals(3, resolved.columns(), "the panel's restated spread");
        assertEquals(1, resolved.rowsPerColumn(), "the spot's untouched spread");
        assertEquals("Top_Right", resolved.id());
        assertTrue(resolved.rightToLeft());
    }

    @Test
    void aPanelNamingNoSpotStillReadsItsInlineLeavesOverTheDocument() throws Exception {
        HudPanelAsset panel = HudPanelAssetTest.panel("{ \"Position\": { \"Preset\": \"BottomLeft\","
                + " \"OffsetY\": 220 }, \"RowsPerColumn\": 4, \"Gap\": { \"AfterRow\": 1, \"Pixels\": 20 },"
                + " \"Cutout\": { \"Column\": 1, \"Rows\": 1 }, \"MinHeight\": 50 }", "Activity_Ledger", null, null);

        HudSpot resolved = HudSpot.resolve(panel, STACK, null, spots);

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
        HudPanelAsset panel = HudPanelAssetTest.panel("{ \"Placement\": \"Bottom_Left\","
                + " \"Position\": { \"OffsetY\": 90 }, \"Columns\": 1, \"Gap\": { \"AfterRow\": 2 },"
                + " \"Cutout\": { \"Rows\": 1 }, \"MinHeight\": 70 }", "World_Bars", null, null);

        HudSpot resolved = HudSpot.resolve(panel, GRID, "top_right", spots);

        assertEquals(HudPosition.HorizontalEdge.RIGHT, resolved.position().getHorizontalEdge());
        assertEquals(10, resolved.position().getOffsetY(),
                "the owner's nudge belonged to the owner's corner and does not follow the pick");
        assertEquals(6, resolved.columns(), "the pick's own spread, not the owner's inline one");
        assertNull(resolved.gap(), "the pick leaves no band, and the owner's inline one does not follow");
        assertNull(resolved.cutout(), "the pick leaves no cut, and the owner's inline one does not follow");
        assertEquals(0, resolved.minHeight(), "nor the owner's floor");
        assertEquals("Top_Right", resolved.id());

        HudSpot picked = HudSpot.resolve(panel, GRID, "bottom_left", spots);
        assertEquals(3, picked.gap().afterRow(), "a pick carries its own spot's band whole");
        assertEquals(48, picked.gap().pixels());
        assertEquals(2, picked.cutout().column(), "and its own spot's cut whole");
        assertEquals(2, picked.cutout().rows(), "the owner's inline Rows does not follow");
        assertEquals(0, picked.minHeight());
        assertEquals(120, HudSpot.resolve(panel, GRID, "top_left", spots).minHeight(),
                "and its own spot's floor");
    }

    @Test
    void aPickThatDoesNotFitIsOffOrIsGoneFallsBackToTheServersChoice() throws Exception {
        HudPanelAsset panel = HudPanelAssetTest.panel("{ \"Placement\": \"Top_Left\" }", "World_Bars", null, null);
        spots.mergeOwnerLayer(Map.of("Top_Right", spot("{ \"Enabled\": false }", "Top_Right")));

        assertEquals("Top_Left", HudSpot.resolve(panel, GRID, "top_right", spots).id(),
                "a spot switched off is as good as gone");
        assertEquals("Top_Left", HudSpot.resolve(panel, GRID, "nowhere", spots).id(),
                "a spot nothing authored");
        assertEquals("Top_Left", HudSpot.resolve(panel, STACK, "bottom_left", spots).id(),
                "a spot measured for another panel");
    }

    @Test
    void aPanelNamingASpotNothingAuthoredSitsWhereTheDocumentSays() throws Exception {
        HudPanelAsset panel = HudPanelAssetTest.panel("{ \"Placement\": \"Elsewhere\","
                + " \"Position\": { \"OffsetX\": 50 } }", "World_Bars", null, null);

        HudSpot resolved = HudSpot.resolve(panel, GRID, null, spots);

        assertEquals(HudPosition.AnchorEdge.TOP, resolved.position().getAnchorEdge(), "the document's corner");
        assertEquals(50, resolved.position().getOffsetX(), "the inline leaf still applies over it");
        assertNull(resolved.id());
    }

    @Test
    void theColumnCapNeverExceedsTheDocument() {
        HudSpot wide = new HudSpot(FALLBACK, 9, 0, null, null, -4, null, null);

        assertEquals(9, wide.columns());
        assertEquals(6, wide.columns(GRID.columns()), "the document declares six");
        assertEquals(1, wide.rowsPerColumn(), "a non-positive spread reads as one");
        assertEquals(0, wide.minHeight(), "a floor below nothing is no floor");
    }

    @Test
    void anUnknownPresetKeepsTheWholeOfTheLayerBelow() {
        HudSpotPosition sideways = new HudSpotPosition("Sideways", 5, 9);
        HudPosition kept = sideways.over(FALLBACK);

        assertSame(FALLBACK, kept, "offsets measured from a corner nobody recognised mean nothing");
        assertEquals(9, new HudSpotPosition(null, null, 9).over(FALLBACK).getOffsetY(), "one leaf, one change");
        assertTrue(new HudSpotPosition(" ", null, null).isEmpty());
    }
}
