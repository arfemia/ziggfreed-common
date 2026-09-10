package com.ziggfreed.common.ui.hud.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.HudPreferenceComponent;
import com.ziggfreed.common.ui.hud.bar.HudBarPanelConfig;
import com.ziggfreed.common.ui.hud.settings.HudSettingsRows.Kind;
import com.ziggfreed.common.ui.hud.settings.HudSettingsRows.Row;

/**
 * The control set of each tab, read off the plan the page appends: Mine is one hide-all switch,
 * then per panel a header, a picker and a show switch, and carries no field at all; Server is per
 * panel a header, the on-for-everyone switch, the spot and one field per inline leaf in the leaves'
 * own order, then the note. Every value a control opens with is the player's own preference or the
 * panel's own file.
 */
class HudSettingsRowsTest {

    private static final List<String> PANELS = List.of("default", "grid");

    @AfterEach
    void clearFold() {
        HudBarPanelConfig.getInstance().mergePackLayer(Map.of());
        HudBarPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private static List<String> shape(List<Row> rows) {
        List<String> shape = new ArrayList<>();
        for (Row row : rows) {
            shape.add(row.kind() + " " + (row.id() != null ? row.id() : row.panelId() != null ? row.panelId() : row.labelKey()));
        }
        return shape;
    }

    @Test
    void theMineTabIsAHideAllSwitchThenAPickerAndAShowSwitchPerPanelAndNoField() {
        HudPreferenceComponent prefs = new HudPreferenceComponent();
        prefs.setPlacement("grid", "TopRight");
        prefs.setHidden("default", true);

        List<Row> rows = HudSettingsRows.mine(prefs, PANELS);

        assertEquals(List.of(
                "TOGGLE hideAll",
                "HEADER default", "DROPDOWN pick:default", "TOGGLE show:default",
                "HEADER grid", "DROPDOWN pick:grid", "TOGGLE show:grid"), shape(rows));
        for (Row row : rows) {
            assertFalse(row.kind() == Kind.FIELD, "no field on Mine: a player picks a spot and nothing more");
        }

        Row hideAll = rows.get(0);
        assertFalse(hideAll.on());
        assertEquals("hide_all", hideAll.labelKey());
        assertEquals("hide_all_hint", hideAll.hintKey());

        Row pickDefault = rows.get(2);
        assertEquals("default", pickDefault.panelId(), "the picker lists that panel's spots");
        assertEquals("server_spot", pickDefault.noneKey(), "the first entry is the server's choice");
        assertEquals("spot_hint", pickDefault.hintKey());
        assertEquals(HudSettingsRows.NONE, pickDefault.value(), "no pick of their own");
        assertFalse(rows.get(3).on(), "hidden by the player, so the show switch is off");

        assertEquals("topright", rows.get(5).value(), "their pick, as the preference keeps it");
        assertTrue(rows.get(6).on());
    }

    @Test
    void aPlayerWithNoPreferenceSeesEverythingShownAndNoPick() {
        List<Row> rows = HudSettingsRows.mine(null, PANELS);

        assertEquals(7, rows.size());
        assertFalse(rows.get(0).on(), "hide-all off");
        assertEquals(HudSettingsRows.NONE, rows.get(2).value());
        assertTrue(rows.get(3).on());
        assertEquals(HudSettingsRows.NONE, rows.get(5).value());
        assertTrue(rows.get(6).on());

        HudPreferenceComponent prefs = new HudPreferenceComponent();
        prefs.setHideAll(true);
        assertTrue(HudSettingsRows.mine(prefs, PANELS).get(0).on());
    }

    @Test
    void theServerTabIsPerPanelTheSwitchTheSpotAndEveryLeafThenTheNote() throws Exception {
        HudBarPanelConfig.getInstance().mergePackLayer(Map.of("Grid", HudServerLeafTest.panel(
                "{ \"Enabled\": false, \"Placement\": \"BottomLeft\", \"Gap\": { \"AfterRow\": 3, \"Pixels\": 66 },"
                        + " \"Color\": \"#ffffffb8\" }")));

        List<Row> rows = HudSettingsRows.server(PANELS, HudBarPanelConfig.getInstance());

        List<String> expected = new ArrayList<>();
        for (String panel : PANELS) {
            expected.add("HEADER " + panel);
            expected.add("TOGGLE enabled:" + panel);
            expected.add("DROPDOWN placement:" + panel);
            for (HudServerLeaf leaf : HudServerLeaf.values()) {
                expected.add("FIELD " + leaf.rowId(panel));
            }
        }
        expected.add("NOTE server_note");
        assertEquals(expected, shape(rows));

        int grid = 3 + HudServerLeaf.values().length;
        assertTrue(rows.get(1).on(), "a panel stating nothing is on");
        assertEquals(HudSettingsRows.NONE, rows.get(2).value(), "and names no spot: as the shipped file says");
        assertEquals("shipped_spot", rows.get(2).noneKey());
        assertEquals("server_spot_hint", rows.get(2).hintKey());
        assertFalse(rows.get(grid + 1).on(), "the grid's file switched it off");
        assertEquals("bottomleft", rows.get(grid + 2).value(), "the spot it names, folded lower-case as the entries are");

        for (int i = 0; i < HudServerLeaf.values().length; i++) {
            HudServerLeaf leaf = HudServerLeaf.values()[i];
            Row defaultField = rows.get(3 + i);
            Row gridField = rows.get(grid + 3 + i);
            assertEquals(leaf.labelKey(), defaultField.labelKey());
            assertEquals(leaf.hintKey(), gridField.hintKey(), "only the colour carries a line under it");
            assertEquals("", defaultField.value(), leaf.name() + " on a panel stating nothing");
        }
        assertEquals("3", rows.get(grid + 3 + HudServerLeaf.GAP_AFTER_ROW.ordinal()).value());
        assertEquals("66", rows.get(grid + 3 + HudServerLeaf.GAP_PIXELS.ordinal()).value());
        assertEquals("", rows.get(grid + 3 + HudServerLeaf.MIN_HEIGHT.ordinal()).value());
        assertEquals("#ffffffb8", rows.get(grid + 3 + HudServerLeaf.COLOR.ordinal()).value());
        assertNull(rows.get(rows.size() - 1).id(), "the note is no control");
    }
}
