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

import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.ui.hud.settings.HudSettingsRows.Row;

/**
 * The control set of the Server page, read off the plan the page appends: per panel a header, the
 * on-for-everyone switch, the spot and one field per inline leaf in the leaves' own order, then the
 * note. Every value a control opens with is the panel's own file.
 */
class HudSettingsRowsTest {

    private static final List<String> PANELS = List.of("Activity_Ledger", "World_Bars");

    @AfterEach
    void clearFold() {
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private static List<String> shape(List<Row> rows) {
        List<String> shape = new ArrayList<>();
        for (Row row : rows) {
            shape.add(row.kind() + " " + (row.id() != null ? row.id() : row.panelId() != null ? row.panelId() : row.labelKey()));
        }
        return shape;
    }

    @Test
    void theServerTabIsPerPanelTheSwitchTheSpotAndEveryLeafThenTheNote() throws Exception {
        HudPanelConfig.getInstance().mergePackLayer(Map.of("World_Bars", HudServerLeafTest.panel(
                "{ \"Enabled\": false, \"Placement\": \"Bottom_Left\", \"Gap\": { \"AfterRow\": 3, \"Pixels\": 66 },"
                        + " \"Color\": \"#ffffffb8\" }")));

        List<Row> rows = HudSettingsRows.server(PANELS, HudPanelConfig.getInstance());

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
        assertFalse(rows.get(grid + 1).on(), "the World bars' file switched it off");
        assertEquals("bottom_left", rows.get(grid + 2).value(), "the spot it names, folded lower-case as the entries are");

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
