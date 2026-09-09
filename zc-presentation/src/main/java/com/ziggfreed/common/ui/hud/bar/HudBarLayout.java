package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;

import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * Everything that differs between one bar panel and another: which document it draws, what its
 * elements are called, how many slots that document declares and how its columns are measured. The
 * drawing itself is identical for every panel, so it lives once in {@link HudBarHud} and reads these
 * numbers rather than repeating itself per panel.
 *
 * <p><b>Every number here mirrors something the document states, and the two must move together.</b>
 * A HUD update can repaint an element the document already declares but can never add one, so
 * {@link #columns} and {@link #slotsPerColumn} are not preferences: they count the blocks physically
 * written in the {@code .ui}, and a panel can never draw more rows than that however it is authored.
 * The widths are mirrors too, because a fill is drawn by pushing a pixel width and one pushed past
 * its track simply overflows it.
 *
 * @param panelId           the {@link HudBarPanelAsset} id this panel reads its authored leaves from
 * @param hudKey            its key on the native per-player {@code HudManager}
 * @param template          the document appended at build time
 * @param root              the selector of the panel element inside that document
 * @param columns           how many columns of slots the document declares
 * @param slotsPerColumn    how many slots each of those columns declares
 * @param paddingPx         the panel's horizontal padding, one side
 * @param columnWidthPx     one column's width
 * @param columnGapPx       the gap between two columns
 * @param trackInnerWidthPx a fill's full width, matching the document's {@code #Track}
 * @param defaultPosition   where the panel sits when nothing is authored for it
 */
public record HudBarLayout(@Nonnull String panelId, @Nonnull String hudKey, @Nonnull String template,
        @Nonnull String root, int columns, int slotsPerColumn, int paddingPx, int columnWidthPx,
        int columnGapPx, int trackInnerWidthPx, @Nonnull HudPosition defaultPosition) {

    /** Every slot the document declares, across all its columns: the ceiling on what can be drawn. */
    public int totalSlots() {
        return columns * slotsPerColumn;
    }

    /**
     * How wide the panel is while {@code shown} of its columns are in use. A panel that has opened
     * only one column must not draw its background at the width of all of them, or an empty gutter
     * sits beside the rows, so the width is pushed with every paint rather than fixed in the
     * document.
     */
    public int panelWidthFor(int shown) {
        int used = Math.max(1, Math.min(shown, columns));
        return paddingPx * 2 + used * columnWidthPx + (used - 1) * columnGapPx;
    }

    /** The panel at its widest, every column in use: what the document's own fallback anchor states. */
    public int panelWidthPx() {
        return panelWidthFor(columns);
    }

    /**
     * The selector of the slot at {@code row} within {@code column}. Slots are positional and
     * addressed by index, so this is the one place their naming is written.
     */
    @Nonnull
    public String slotSelector(int column, int row) {
        return columnSelector(column) + " #ZigBar" + column + "_" + row;
    }

    /** The selector of a whole column, hidden entirely when the rows in play do not reach it. */
    @Nonnull
    public String columnSelector(int column) {
        return "#ZigBarCol" + column;
    }
}
