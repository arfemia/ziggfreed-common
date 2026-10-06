package com.ziggfreed.common.settings.page;

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * The Settings tab's numbers, which its documents spell too ({@code ZigCardsDocumentTest} holds the two
 * together): the paddings around the cards and the tile's footprint, from which the tile grid's height
 * is worked out for the tiles a build actually paints, never hand-counted.
 */
public final class SettingsLayout {

    /** {@code #SettingsPanel}'s left padding, beside the rail. */
    public static final int PANEL_PADDING_LEFT = 12;

    /** {@code #Sections}' right padding, the scrollbar's room. */
    public static final int SECTIONS_PADDING_RIGHT = 8;

    /** {@code @ZigSectionCard}'s padding, all four sides. */
    public static final int CARD_PADDING = 12;

    /** {@code @ZigTile}'s size and the spacing after it. */
    public static final int TILE_WIDTH = 210;
    public static final int TILE_HEIGHT = 104;
    public static final int TILE_RIGHT = 8;
    public static final int TILE_BOTTOM = 8;

    /** The height one wrapped row of tiles takes. */
    public static final int TILE_ROW_HEIGHT = TILE_HEIGHT + TILE_BOTTOM;

    /** How many tiles sit across a card at the frame's body width. */
    public static final int TILES_PER_ROW = Math.max(1,
            (MenuFrame.BODY_WIDTH - PANEL_PADDING_LEFT - SECTIONS_PADDING_RIGHT - 2 * CARD_PADDING)
                    / (TILE_WIDTH + TILE_RIGHT));

    private SettingsLayout() {
    }

    /** The grid height {@code tiles} tiles take: one row at least, then a row per {@link #TILES_PER_ROW}. */
    public static int tileGridHeight(int tiles) {
        int rows = Math.max(1, (tiles + TILES_PER_ROW - 1) / TILES_PER_ROW);
        return rows * TILE_ROW_HEIGHT;
    }
}
