package com.ziggfreed.common.ui.hud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * The centred panel: the third bar panel every player carries, for a value that moved while they had
 * a page open. It draws the Activity ledger's own document under its own name and key, attaches last
 * so it draws over the other two, hangs from the top edge at the screen's horizontal centre in one
 * short column, and holds a row moved while a page is open until no page is.
 */
class CenterPanelTest {

    private static final Path SHIPPED = Path.of("src", "main", "resources", "Server", "ZiggfreedCommon", "HudPanels",
            "Center_Bars.json");

    @AfterEach
    void clearFolds() {
        HudPanelConfig.getInstance().mergePackLayer(Map.of());
        HudPanelConfig.getInstance().mergeOwnerLayer(Map.of());
        HudSpotConfig.getInstance().mergePackLayer(Map.of());
        HudSpotConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    void itDrawsTheLedgersDocumentUnderItsOwnNameAndLayer() {
        HudPanelLayout center = CenterPanelHud.LAYOUT;
        HudPanelLayout ledger = LedgerPanelHud.LAYOUT;

        assertEquals(HudPanelAsset.CENTER_ID, center.panelId());
        assertNotEquals(ledger.hudKey(), center.hudKey(), "a layer of its own: one key is one HUD per player");
        assertEquals(new HudPanelLayout(center.panelId(), center.hudKey(), ledger.template(), ledger.root(),
                ledger.columns(), ledger.slotsPerColumn(), ledger.paddingPx(), ledger.columnWidthPx(),
                ledger.columnGapPx(), ledger.trackInnerWidthPx(), ledger.verticalPaddingPx(), ledger.rowMarginPx(),
                ledger.lineHeightPx(), ledger.barBlockPx(), center.defaultPosition()), center,
                "every number mirrors the document it draws, and that document is the ledger's");
        assertEquals(HudPosition.AnchorEdge.TOP, center.defaultPosition().getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.CENTER, center.defaultPosition().getHorizontalEdge());
        assertEquals(0, center.defaultPosition().getOffsetX(), "dead centre");
    }

    @Test
    void itAttachesLastSoItDrawsOverTheOtherTwo() {
        assertEquals(List.of(HudPanelAsset.LEDGER_ID, HudPanelAsset.WORLD_ID, HudPanelAsset.CENTER_ID),
                HudPanels.panels().stream().map(HudPanelLayout::panelId).toList());
        assertSame(CenterPanelHud.LAYOUT, HudPanels.panel("center_bars"), "a command names it ignoring case");
    }

    @Test
    void theShippedFileHangsItTopCentreInOneShortColumnAndHoldsItWhileAPageIsOpen() throws Exception {
        HudPanelAsset shipped = HudPanelAssetTest.panel(Files.readString(SHIPPED, StandardCharsets.UTF_8),
                HudPanelAsset.CENTER_ID, null, null);

        assertTrue(shipped.enabled());
        assertTrue(shipped.holdsWhilePageOpen(),
                "the client draws no HUD over a page, and most gains are made in one");
        assertNull(shipped.placement(), "no spot was measured for it, so it states its own corner");
        assertEquals("ziggfreedcommon.hud.panel.center_bars", shipped.labelKey());

        HudSpot spot = HudSpot.resolve(shipped, CenterPanelHud.LAYOUT, null, HudSpotConfig.getInstance());
        assertEquals(HudPosition.AnchorEdge.TOP, spot.position().getAnchorEdge(), "a Top pin, as proven in game");
        assertEquals(HudPosition.HorizontalEdge.CENTER, spot.position().getHorizontalEdge());
        assertEquals(0, spot.position().getOffsetX(), "no nudge off the centre line");
        assertTrue(spot.position().getOffsetY() > 0, "down from the top edge, clear of the banners there");
        assertFalse(spot.bottomUp(), "it reads top down");
        assertFalse(spot.rightToLeft());
        assertEquals(1, spot.columns(CenterPanelHud.LAYOUT.columns()), "one column, never a block");

        int cap = shipped.maxVisible(CenterPanelHud.LAYOUT.totalSlots());
        assertTrue(cap >= 1 && cap <= 4, "a short stack, and the most rows that wait under a page: " + cap);
        assertEquals(cap, HudPanelHud.slotCap(shipped, spot, CenterPanelHud.LAYOUT),
                "the one column holds every row the file allows");
    }
}
