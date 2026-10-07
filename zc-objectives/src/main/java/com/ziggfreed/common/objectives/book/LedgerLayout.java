package com.ziggfreed.common.objectives.book;

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * The book's geometry, in one place and derived from the shared frame's body ({@link MenuFrame#BODY_WIDTH}),
 * never a literal: the shell's header band and tab body, and the list-and-page split every tab lays its
 * screen out on. A document cannot read a Java constant, so each document spells the same numbers and a test
 * holds the two together ({@code LedgerLayoutTest} for the shell; each tab's document test for its own).
 *
 * <pre>
 * inner 1266 x 968 (the frame's body less PANEL_PADDING all round)
 *   header 60, then 12
 *   tab body 896: toolbar 32, then 12; split 852 high
 *     list 500 (rows 492 inside its scroll gutter) | gutter 16 | page 750
 * </pre>
 */
public final class LedgerLayout {

    /** {@code #RightPanel}'s padding, all four sides. */
    public static final int PANEL_PADDING = 4;

    /** What the book's own content shares, inside the frame's body. */
    public static final int INNER_WIDTH = MenuFrame.BODY_WIDTH - 2 * PANEL_PADDING;
    public static final int INNER_HEIGHT = MenuFrame.FRAME_HEIGHT - 2 * MenuFrame.FRAME_PADDING - 2 * PANEL_PADDING;

    /** The header band ({@code #Header}): the page title, its subtitle and the stats; then a gap. */
    public static final int HEADER_HEIGHT = 60;
    public static final int HEADER_GAP = 12;

    /** The header's stat blocks ({@code $ZW.@ZigStat #Stat0..#Stat2}) and the gap between two. */
    public static final int STATS = 3;
    public static final int STAT_WIDTH = 150;
    public static final int STAT_GAP = 8;

    /** The tab body ({@code #TabBody}) a tab's document fills, under the header. */
    public static final int BODY_HEIGHT = INNER_HEIGHT - HEADER_HEIGHT - HEADER_GAP;

    /** A tab's toolbar row (segments, dropdowns, the search row, vanilla's small control height); then a gap. */
    public static final int TOOLBAR_HEIGHT = 32;
    public static final int TOOLBAR_GAP = 12;

    /** The list-and-page split under the toolbar. */
    public static final int SPLIT_HEIGHT = BODY_HEIGHT - TOOLBAR_HEIGHT - TOOLBAR_GAP;

    /** A scrolling column's right padding, the room its scrollbar takes. */
    public static final int SCROLL_GUTTER = 8;

    /** The list column, and the width its rows get inside the scroll gutter. */
    public static final int LIST_WIDTH = 500;
    public static final int ROW_WIDTH = LIST_WIDTH - SCROLL_GUTTER;

    /** Between the list and the page. */
    public static final int GUTTER = 16;

    /** The selected row's page card. */
    public static final int PAGE_WIDTH = INNER_WIDTH - LIST_WIDTH - GUTTER;

    private LedgerLayout() {
    }
}
