package com.ziggfreed.common.objectives.book;

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * The widths the book's chips-or-dropdown rule measures, derived from the shared frame's one body width
 * ({@link MenuFrame#BODY_WIDTH}) rather than a literal: the frame owns the size and the rail, the book owns
 * only its right panel's padding and its side column, both its document's own numbers (BookWidthsTest).
 */
final class BookWidths {

    /** {@code #RightPanel}'s padding, both sides ({@code Padding: (Full: 20)}). */
    static final int RIGHT_PANEL_PADDING = 40;

    /** {@code #SidePanel}'s width. */
    static final int SIDE_PANEL_WIDTH = 320;

    /** {@code Pages/ZigBookCatTab.ui}'s #CatBtn width (96) plus its leading spacer (6). */
    static final int CAT_TAB_OUTER_WIDTH = 102;

    /** {@code Pages/ZigBookWideTab.ui}'s #CatBtn width (160) plus its leading spacer (6). */
    static final int WIDE_TAB_OUTER_WIDTH = 166;

    private BookWidths() {
    }

    /** The width the active tab's filter strip gets, with or without the side column painted. */
    static int stripWidthBudget(boolean sidePanelPainted) {
        return MenuFrame.BODY_WIDTH - RIGHT_PANEL_PADDING - (sidePanelPainted ? SIDE_PANEL_WIDTH : 0);
    }

    /**
     * The ONE chips-vs-dropdown rule, shared by both tabs: chips render only when every chip, the All chip
     * included, fits the strip on one line; otherwise the native dropdown carries the categories.
     */
    static boolean categoryChipsFit(int categoryCount, int chipOuterWidth, int widthBudget) {
        return (categoryCount + 1) * chipOuterWidth <= widthBudget;
    }
}
