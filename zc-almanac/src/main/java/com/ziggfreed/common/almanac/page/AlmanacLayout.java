package com.ziggfreed.common.almanac.page;

import com.ziggfreed.common.almanac.view.AlmanacView;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.ZigTokens;
import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * The Almanac page's numbers, in one place: {@code Pages/ZigAlmanacPage.ui} spells the same values (a
 * document cannot read a Java constant) and {@code AlmanacPageDocumentTest} holds the two together. The
 * page owns the frame's body beside the rail ({@link MenuFrame#BODY_WIDTH} by the frame's inner height):
 * a 300 column on the left (the season list, the year at a glance, the record card), a 12 gap, and a 962
 * column on the right (the 962 x 240 hero over a scrolling body whose content is 906 wide).
 */
public final class AlmanacLayout {

    /** What the page's columns share: the frame's body beside the rail. */
    public static final int BODY_WIDTH = MenuFrame.BODY_WIDTH;
    public static final int BODY_HEIGHT = MenuFrame.FRAME_HEIGHT - 2 * MenuFrame.FRAME_PADDING;

    // ---- the left column ----

    public static final int LEFT_WIDTH = 300;
    public static final int LEFT_PADDING = 12;
    public static final int LEFT_INNER = LEFT_WIDTH - 2 * LEFT_PADDING;

    /** The column's title, the lead under it (two lines), and the space after each. */
    public static final int TITLE_HEIGHT = 28;
    public static final int TITLE_GAP = 4;
    public static final int LEAD_HEIGHT = 34;
    public static final int LEAD_GAP = 12;

    /** The cross-season banner card (Seasons of Orbis), and the space after it. */
    public static final int BANNER_HEIGHT = 84;
    public static final int BANNER_GAP = 12;

    /** The year at a glance: its title and twelve authored month rows, with the space above it. */
    public static final int GLANCE_GAP = 12;
    public static final int GLANCE_TITLE_HEIGHT = 24;
    public static final int MONTH_HEIGHT = 22;
    public static final int MONTHS = 12;
    public static final int GLANCE_HEIGHT = GLANCE_TITLE_HEIGHT + MONTHS * MONTH_HEIGHT;

    /** A season's mark on a month row: an 8 x 8 dot in its accent, and how many one row holds. */
    public static final int MONTH_MARK = 8;
    public static final int MONTH_MARKS_MAX = 12;

    /** The record card, pinned under the list, with the space above it. */
    public static final int RECORD_GAP = 12;
    public static final int RECORD_HEIGHT = 100;

    /**
     * The least the season list keeps: one section head and three of the kit's tall rows it paints, each with the
     * gap under it (266 today).
     */
    public static final int LIST_MIN_HEIGHT = ZigTokens.SECTION_HEAD_HEIGHT
            + 3 * (RowSize.TALL.height() + ZigTokens.SPACE_1);

    // ---- the right column ----

    public static final int GAP = 12;
    public static final int RIGHT_WIDTH = BODY_WIDTH - LEFT_WIDTH - GAP;

    /** The hero plate (the kit's {@code @ZigHeroPlate}). */
    public static final int HERO_WIDTH = AlmanacView.HERO_WIDTH;
    public static final int HERO_HEIGHT = AlmanacView.HERO_HEIGHT;

    /** The scrolling body under the hero: its padding, and the scrollbar's room inside the right padding. */
    public static final int BODY_PAD_LEFT = 24;
    public static final int BODY_PAD_RIGHT = 24;
    public static final int BODY_PAD_TOP = 16;
    public static final int SCROLL_GUTTER = 8;
    public static final int CONTENT_WIDTH = RIGHT_WIDTH - BODY_PAD_LEFT - BODY_PAD_RIGHT - SCROLL_GUTTER;

    /** A stat tile (the kit's {@code Pages/ZigStatTile.ui}) with its margin, and how many a row holds. */
    public static final int STAT_TILE_STEP = 216 + 8;
    public static final int STAT_TILES_PER_ROW = 4;

    /** A keepsake tile (the kit's {@code Pages/ZigKeepsakeTile.ui}) with its margin, and how many a row holds. */
    public static final int KEEPSAKE_STEP = 112 + 8;
    public static final int KEEPSAKES_PER_ROW = 7;

    /** A year chip (the kit's {@code Pages/ZigSegment.ui}) with its margin. */
    public static final int YEAR_CHIP_STEP = 132 + 6;

    /** The season's link buttons, authored and filled in order; a season's links past these are not shown. */
    public static final int LINK_SLOTS = 4;
    public static final int LINK_WIDTH = 200;
    public static final int LINK_STEP = LINK_WIDTH + 8;

    /**
     * The least width or height a hero glow keeps once it is fitted onto the plate (the client does not clip a
     * child to its parent, so a glow is drawn as the largest box about its centre that the plate holds); smaller,
     * it hides.
     */
    public static final int GLOW_MIN_FITTED = 16;

    private AlmanacLayout() {
    }
}
