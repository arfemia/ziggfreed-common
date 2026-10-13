package com.ziggfreed.common.ui.hud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Which panel a settings page lists first is the panel's own {@code Order}, read off the fold, and
 * never the order the library attaches the panels in: the attach order decides what draws over
 * what where two overlap and is fixed in Java. The shipped files list the World bars ahead of the
 * Activity ledger; the numbers below are otherwise the test's own.
 */
class HudPanelsListingTest {

    private static final Path SHIPPED = Path.of("src", "main", "resources", "Server", "ZiggfreedCommon", "HudPanels");

    @AfterEach
    void clearFold() {
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private static HudPanelAsset panel(String id, int order) throws Exception {
        return HudPanelAssetTest.panel("{ \"Order\": " + order + " }", id, null, null);
    }

    private static List<String> attached() {
        return HudPanels.panels().stream().map(HudPanelLayout::panelId).toList();
    }

    @Test
    void panelsNamingNoOrderListByIdAndAnEmptyFoldStillAnswers() {
        assertEquals(HudPanelAsset.DEFAULT_ORDER, HudPanelAsset.defaults().order());
    }

    @Test
    void theAttachOrderIsNotWhatTheTabsSortBy() throws Exception {
        List<String> before = attached();

        HudPanelConfig fold = HudPanelConfig.getInstance();
        fold.mergePackLayer(Map.of(
                HudPanelAsset.WORLD_ID, panel(HudPanelAsset.WORLD_ID, 10),
                HudPanelAsset.LEDGER_ID, panel(HudPanelAsset.LEDGER_ID, 20)));
        assertNotEquals(before, HudPanels.listing(fold), "the listing followed the Order leaves");
        assertEquals(before, attached(), "and the attach order did not move with them");
    }

    @Test
    void theShippedFilesListTheWorldBarsAheadOfTheActivityLedger() throws Exception {
        int world = shippedOrder("World_Bars.json");
        int ledger = shippedOrder("Activity_Ledger.json");
        assertTrue(world < ledger, "World_Bars (" + world + ") lists ahead of Activity_Ledger (" + ledger + ")");
    }

    private static int shippedOrder(String file) throws Exception {
        JsonObject root = JsonParser.parseString(
                Files.readString(SHIPPED.resolve(file), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(root.has("Order"), file + " names an Order");
        return root.get("Order").getAsInt();
    }
}
