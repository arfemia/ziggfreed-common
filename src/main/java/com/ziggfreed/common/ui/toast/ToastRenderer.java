package com.ziggfreed.common.ui.toast;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Single source of truth for the {@code #ZigToast} element-id contract over the shared
 * {@code Pages/ZigToast.ui} fragment (appended once per page by
 * {@code ToastablePage.renderToastInto}). Both the in-page overlay and a future HUD overlay
 * write through this, over the same markup block, so the contract lives in exactly one place.
 *
 * <p><b>Show/hide via Anchor collapse.</b> The toast Group is always {@code Visible} (never
 * toggled - a page {@code sendUpdate} cannot drive {@code Visible} {@code false->true}).
 * Instead {@link #apply} expands its {@code Anchor} to the full panel size and
 * {@link #applyIdle} collapses it to {@code 0x0} (the engine-sanctioned "zero-size styling"
 * that IS settable over {@code sendUpdate}). A collapsed {@code 0x0} box renders nothing, so
 * the bordered panel is invisible when idle. This lets a toast show without a page reopen,
 * preserving scroll position.
 *
 * <p><b>Body rows.</b> The fragment pre-bakes {@link #MAX_LINES} reward rows
 * ({@code #ZigToastRow<i>}: a 24px {@code ItemGrid} icon + a Label), because a
 * {@code sendUpdate} can only restyle existing elements, never append new ones. A used row
 * expands its Anchor and fills text + icon slots; an unused row collapses to {@code 0x0} with
 * cleared text/slots so it can never paint. An icon-less line collapses just the row's icon
 * cell (same Anchor trick).
 */
public final class ToastRenderer {

    /** Body row capacity. MUST match the {@code #ZigToastRow<i>} count in {@code ZigToast.ui}. */
    public static final int MAX_LINES = 6;

    // WIDTH/HEIGHT/ROW_* mirror the static #ZigToast geometry in ZigToast.ui. Top is no longer a
    // constant: the toast is centered in the top third of the screen (see topFor), so apply()
    // recomputes it from the panel height each show.
    private static final int TOP_THIRD_CENTER = 180; // ~1080 canvas / 6; tune in-game if needed
    private static final int WIDTH = 460;
    // Base panel height = outer frame rim (Padding Full 12 -> 24) + inner padding (Vertical 16 -> 32)
    // + the fixed headline height (28). Each visible row then adds its top gap + ROW_H; row 0 uses
    // TITLE_GAP (the title->reward gap), later rows ROW_GAP. Sized to fit content exactly (no
    // FlexWeight slack) so the panel never shows empty space below the last row. MUST match ZigToast.ui.
    private static final int HEIGHT = 84;
    private static final int TITLE_GAP = 16;
    private static final int ROW_GAP = 6;
    private static final int ROW_H = 28;
    private static final int ICON_W = 24;

    private ToastRenderer() {}

    /** Show the toast: expand the panel + set the headline, kind color and body rows. The
     *  headline + each row text are client-resolved {@link com.hypixel.hytale.server.core.Message}s. */
    public static void apply(@Nonnull UICommandBuilder cmd, @Nonnull ToastSpec spec) {
        List<ToastLine> lines = spec.lines();
        cmd.setObject("#ZigToast.Anchor", anchor(WIDTH, panelHeight(lines.size())));
        // Text from a Message rides TextSpans, NOT .Text: a Label's .Text is a client String
        // property that cannot construct from a raw/composite Message object (it aborts the whole
        // CustomUI update). TextSpans is the Message sink (it encodes the full FormattedMessage,
        // renders any markup per-locale, and renders plain Messages unchanged - the dialogue page
        // pattern). The kind colour stays on the base Style.TextColor (the Message carries no colour).
        cmd.set("#ZigToastText.TextSpans", spec.message());
        cmd.set("#ZigToastText.Style.TextColor", spec.kind().textColor());
        // Tint the white frame texture to the kind color (gold REWARD / green SUCCESS / red ERROR).
        cmd.set("#ZigToast.Background.Color", spec.kind().textColor());
        for (int i = 0; i < MAX_LINES; i++) {
            setRow(cmd, i, i < lines.size() ? lines.get(i) : null);
        }
    }

    /** Hide the toast: collapse the panel to 0x0 (invisible) and clear all content. */
    public static void applyIdle(@Nonnull UICommandBuilder cmd) {
        cmd.setObject("#ZigToast.Anchor", anchor(0, 0));
        cmd.set("#ZigToastText.TextSpans", Message.empty());
        for (int i = 0; i < MAX_LINES; i++) {
            setRow(cmd, i, null);
        }
    }

    /** Fill + expand one body row, or ({@code line == null}) collapse + clear it. */
    private static void setRow(@Nonnull UICommandBuilder cmd, int index, @Nullable ToastLine line) {
        String sel = "#ZigToastRow" + index;
        if (line == null) {
            cmd.setObject(sel + ".Anchor", anchor(0, 0));
            cmd.set(sel + " #RowText.TextSpans", Message.empty());
            cmd.set(sel + " #RowIcon.Slots", List.<ItemGridSlot>of());
            return;
        }
        cmd.setObject(sel + ".Anchor", rowAnchor(index));
        cmd.set(sel + " #RowText.TextSpans", line.text());
        if (line.hasIcon()) {
            cmd.setObject(sel + " #RowIcon.Anchor", anchorWh(ICON_W, ICON_W));
            cmd.set(sel + " #RowIcon.Slots", List.of(
                    new ItemGridSlot(new ItemStack(line.iconItemId(), Math.max(1, line.quantity())))));
        } else {
            // Text-only line: collapse the icon cell so no empty slot frame paints.
            cmd.setObject(sel + " #RowIcon.Anchor", anchorWh(0, 0));
            cmd.set(sel + " #RowIcon.Slots", List.<ItemGridSlot>of());
        }
    }

    @Nonnull
    private static Anchor anchor(int width, int height) {
        // Width-without-Horizontal centers horizontally (native EventTitle pattern); adding
        // Horizontal here would stretch the panel to full width. Top is computed so the panel is
        // centered in the top third of the screen.
        Anchor a = new Anchor();
        a.setTop(Value.of(topFor(height)));
        a.setWidth(Value.of(width));
        a.setHeight(Value.of(height));
        return a;
    }

    /** Top offset that centers a panel of the given height in the top third of the screen. */
    private static int topFor(int height) {
        return Math.max(12, TOP_THIRD_CENTER - height / 2);
    }

    /** Panel height that exactly fits the fixed headline + the visible rows. Row 0 carries the
     *  TITLE_GAP (title->reward gap), later rows ROW_GAP. No FlexWeight slack, so the panel never
     *  shows empty space below the last row. */
    private static int panelHeight(int rows) {
        int height = HEIGHT;
        for (int i = 0; i < rows; i++) {
            height += (i == 0 ? TITLE_GAP : ROW_GAP) + ROW_H;
        }
        return height;
    }

    /** Expanded body-row anchor: top gap + fixed height, flexible width. Row 0 uses the larger
     *  TITLE_GAP so the first reward sits clear of the headline; later rows use ROW_GAP. */
    @Nonnull
    private static Anchor rowAnchor(int index) {
        Anchor a = new Anchor();
        a.setTop(Value.of(index == 0 ? TITLE_GAP : ROW_GAP));
        a.setHeight(Value.of(ROW_H));
        return a;
    }

    /** A plain width x height anchor (no margins), e.g. the row icon cell. */
    @Nonnull
    private static Anchor anchorWh(int width, int height) {
        Anchor a = new Anchor();
        a.setWidth(Value.of(width));
        a.setHeight(Value.of(height));
        return a;
    }
}
