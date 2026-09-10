package com.ziggfreed.common.ui.hud.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.hud.panel.HudPanelAsset;

/**
 * The Server tab's fields, each a leaf of the owner file: the ten are listed in the tab's order on
 * their own rows and paths; a typed value lands on its own leaf as the codec expects it (the colour
 * normalised); a blank field is a removal; a letter in a number field and a value that is not a hex
 * each refuse the ONE field they name and hand back nothing to write, so the rest of the draft is
 * kept for the admin to fix it; and what a field shows on opening is the panel's own leaf, a stated
 * zero included, or blank for none. Every number below is the test's own.
 */
class HudServerLeafTest {

    private static final String PANEL = "World_Bars";

    static HudPanelAsset panel(String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudPanelAsset.class, PANEL, null);
        return HudPanelAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    /** Every field of the panel typed as an admin might: some negative, the colour without its hash and in capitals. */
    static Map<String, String> typed() {
        Map<String, String> draft = new LinkedHashMap<>();
        draft.put("offsetx:" + PANEL, "16");
        draft.put("offsety:" + PANEL, "-4");
        draft.put("columns:" + PANEL, "3");
        draft.put("rows:" + PANEL, "1");
        draft.put("gaprow:" + PANEL, "3");
        draft.put("gappx:" + PANEL, " 66 ");
        draft.put("cutcol:" + PANEL, "3");
        draft.put("cutrows:" + PANEL, "2");
        draft.put("minheight:" + PANEL, "124");
        draft.put("color:" + PANEL, "AABBCCDD");
        return draft;
    }

    @Test
    void theTenLeavesAreListedInTheTabsOrderEachOnItsOwnRowAndPath() {
        List<String> rows = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (HudServerLeaf leaf : HudServerLeaf.values()) {
            rows.add(leaf.rowId(PANEL));
            paths.add(leaf.path());
        }
        assertEquals(List.of("offsetx:World_Bars", "offsety:World_Bars", "columns:World_Bars", "rows:World_Bars", "gaprow:World_Bars",
                "gappx:World_Bars", "cutcol:World_Bars", "cutrows:World_Bars", "minheight:World_Bars", "color:World_Bars"), rows,
                "the offsets, the spread, the band, the cut, the least height, the colour");
        assertEquals(List.of("Position.OffsetX", "Position.OffsetY", "Columns", "RowsPerColumn", "Gap.AfterRow",
                "Gap.Pixels", "Cutout.Column", "Cutout.Rows", "MinHeight", "Color"), paths);
        assertEquals(HudServerLeaf.Kind.COLOR, HudServerLeaf.COLOR.kind());
        for (HudServerLeaf leaf : HudServerLeaf.values()) {
            if (leaf != HudServerLeaf.COLOR) {
                assertEquals(HudServerLeaf.Kind.WHOLE_NUMBER, leaf.kind(), leaf.name());
            }
        }
        assertEquals("color_hint", HudServerLeaf.COLOR.hintKey(), "the one field whose format needs a line");
        assertNull(HudServerLeaf.GAP_PIXELS.hintKey());
    }

    @Test
    void everyTypedValueLandsOnItsOwnLeafAsTheCodecExpectsIt() {
        HudServerLeaf.Draft drafted = HudServerLeaf.draft(PANEL, typed());

        assertTrue(drafted.accepted());
        assertNull(drafted.refused());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("Position.OffsetX", 16);
        expected.put("Position.OffsetY", -4);
        expected.put("Columns", 3);
        expected.put("RowsPerColumn", 1);
        expected.put("Gap.AfterRow", 3);
        expected.put("Gap.Pixels", 66);
        expected.put("Cutout.Column", 3);
        expected.put("Cutout.Rows", 2);
        expected.put("MinHeight", 124);
        expected.put("Color", "#aabbccdd");
        assertEquals(expected, drafted.leaves());
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(drafted.leaves().keySet()),
                "written in the tab's order, so the file reads as the page does");
        assertEquals(Integer.class, drafted.leaves().get("Gap.Pixels").getClass(),
                "a whole number is boxed as one, so the file carries 66 and not 66.0");
    }

    @Test
    void aBlankOrAbsentFieldRemovesItsLeaf() {
        Map<String, String> draft = new LinkedHashMap<>();
        draft.put("gappx:" + PANEL, "   ");
        draft.put("color:" + PANEL, "");

        HudServerLeaf.Draft drafted = HudServerLeaf.draft(PANEL, draft);

        assertTrue(drafted.accepted());
        assertEquals(HudServerLeaf.values().length, drafted.leaves().size(), "every leaf is named");
        for (HudServerLeaf leaf : HudServerLeaf.values()) {
            assertTrue(drafted.leaves().containsKey(leaf.path()), leaf.path());
            assertNull(drafted.leaves().get(leaf.path()), leaf.path() + " is a removal");
        }
    }

    @Test
    void aLetterInANumberFieldRefusesThatFieldAndHandsBackNothingToWrite() {
        Map<String, String> draft = typed();
        draft.put("gappx:" + PANEL, "tall");

        HudServerLeaf.Draft drafted = HudServerLeaf.draft(PANEL, draft);

        assertFalse(drafted.accepted());
        assertSame(HudServerLeaf.GAP_PIXELS, drafted.refused(), "named, so the toast can say which");
        assertEquals("invalid_number", drafted.refused().kind().refusalKey());
        assertEquals("gap_pixels", drafted.refused().labelKey());
        assertTrue(drafted.leaves().isEmpty(), "nothing at all is written, the good fields included");

        draft = typed();
        draft.put("minheight:" + PANEL, "1.5");
        assertSame(HudServerLeaf.MIN_HEIGHT, HudServerLeaf.draft(PANEL, draft).refused(), "a whole number, not a fraction");

        assertThrows(IllegalArgumentException.class, () -> HudServerLeaf.COLUMNS.value("two"));
    }

    @Test
    void aColourThatIsNotAHexRefusesThatFieldAndHandsBackNothingToWrite() {
        Map<String, String> draft = typed();
        draft.put("color:" + PANEL, "#12345");

        HudServerLeaf.Draft drafted = HudServerLeaf.draft(PANEL, draft);

        assertFalse(drafted.accepted());
        assertSame(HudServerLeaf.COLOR, drafted.refused());
        assertEquals("invalid_color", drafted.refused().kind().refusalKey(), "its own line, not the number's");
        assertEquals("color", drafted.refused().labelKey());
        assertTrue(drafted.leaves().isEmpty(), "the nine numbers that read are held back with it");

        assertThrows(IllegalArgumentException.class, () -> HudServerLeaf.COLOR.value("#fff"), "a short form");
        assertThrows(IllegalArgumentException.class, () -> HudServerLeaf.COLOR.value("red"), "a name");
        assertEquals("#ffffffb8", HudServerLeaf.COLOR.value("ffffffB8"), "no hash, capitals: the same colour");
        assertEquals("#aabbcc", HudServerLeaf.COLOR.value(" #AABBCC "), "surrounding space is ignored");
        assertNull(HudServerLeaf.COLOR.value(null), "nothing typed is a removal");
    }

    @Test
    void whatAFieldShowsIsThePanelsOwnLeafAStatedZeroIncludedAndBlankForNone() throws Exception {
        HudPanelAsset bare = HudPanelAsset.defaults();
        for (HudServerLeaf leaf : HudServerLeaf.values()) {
            assertEquals("", leaf.shown(bare), leaf.name() + " on a panel stating nothing");
        }

        HudPanelAsset full = panel("{ \"Position\": { \"OffsetX\": 16, \"OffsetY\": -4 }, \"Columns\": 3,"
                + " \"RowsPerColumn\": 1, \"Gap\": { \"AfterRow\": 3, \"Pixels\": 66 },"
                + " \"Cutout\": { \"Column\": 3, \"Rows\": 2 }, \"MinHeight\": 124, \"Color\": \"#AABBCCDD\" }");
        assertEquals("16", HudServerLeaf.OFFSET_X.shown(full));
        assertEquals("-4", HudServerLeaf.OFFSET_Y.shown(full));
        assertEquals("3", HudServerLeaf.COLUMNS.shown(full));
        assertEquals("1", HudServerLeaf.ROWS_PER_COLUMN.shown(full));
        assertEquals("3", HudServerLeaf.GAP_AFTER_ROW.shown(full));
        assertEquals("66", HudServerLeaf.GAP_PIXELS.shown(full));
        assertEquals("3", HudServerLeaf.CUTOUT_COLUMN.shown(full));
        assertEquals("2", HudServerLeaf.CUTOUT_ROWS.shown(full));
        assertEquals("124", HudServerLeaf.MIN_HEIGHT.shown(full));
        assertEquals("#aabbccdd", HudServerLeaf.COLOR.shown(full), "normalised, as the file reads it");

        HudPanelAsset off = panel("{ \"Gap\": { \"Pixels\": 0 }, \"Cutout\": { \"Rows\": 0 } }");
        assertEquals("0", HudServerLeaf.GAP_PIXELS.shown(off),
                "a band switched off with a zero shows its zero: a blank would remove it on the next Save");
        assertEquals("", HudServerLeaf.GAP_AFTER_ROW.shown(off), "the leaf the file does not state");
        assertEquals("0", HudServerLeaf.CUTOUT_ROWS.shown(off));
        assertEquals("", HudServerLeaf.CUTOUT_COLUMN.shown(off));

        HudPanelAsset malformed = panel("{ \"Color\": \"#not-a-colour\" }");
        assertEquals("", HudServerLeaf.COLOR.shown(malformed),
                "a value the paint ignores is shown as nothing, so Save clears it rather than keeping it");
    }
}
