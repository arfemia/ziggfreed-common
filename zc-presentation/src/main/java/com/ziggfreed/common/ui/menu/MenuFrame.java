package com.ziggfreed.common.ui.menu;

/**
 * The shared menu frame's one set of numbers. {@code ZigFrames.ui}'s {@code @ZigMenuFrame} and
 * {@code Pages/ZigMenuTab.ui} spell the same values (a document cannot read a Java constant), and
 * {@code ZigMenuDocumentsTest} holds them together; a page on the frame derives its own widths from
 * {@link #BODY_WIDTH}, never a literal. The rail is vanilla's own left rail
 * ({@code Pages/WorldEvent/WorldEventNavButton.ui}, {@code Pages/UIGallery/CategoryButton.ui}).
 */
public final class MenuFrame {

    /** The frame's one size, for every page on the rail. */
    public static final int FRAME_WIDTH = 1570;
    public static final int FRAME_HEIGHT = 1000;

    /** The frame's {@code #Content} padding, all four sides. */
    public static final int FRAME_PADDING = 12;

    /** The rail column, {@link #RAIL}. */
    public static final int RAIL_WIDTH = 250;

    /** The rail pane's padding, all four sides (vanilla's pane padding). */
    public static final int RAIL_PADDING = 8;

    /** Vanilla's vertical separator between the rail and the page ({@code Common.ui}'s {@code @VerticalSeparator}). */
    public static final int SEPARATOR_WIDTH = 6;

    /** The space either side of the separator (vanilla's {@code Pages/Point/PointInspectorPage.ui} spacing). */
    public static final int SEPARATOR_MARGIN = 8;

    /** What a page's own columns share, beside the rail and its separator. */
    public static final int BODY_WIDTH = FRAME_WIDTH - 2 * FRAME_PADDING - RAIL_WIDTH - SEPARATOR_WIDTH
            - 2 * SEPARATOR_MARGIN;

    /** The rail host: a themeable pane (it carries a {@code Background}), holding the branding hosts and the list. */
    public static final String RAIL = "#MenuRail";

    /** The list the rail's rows are appended into, by index. */
    public static final String LIST = "#MenuList";

    /** One rail row: 32 high with 4 below, 10 in from each side (vanilla's row). */
    public static final int ROW_HEIGHT = 32;
    public static final int ROW_GAP = 4;
    public static final int ROW_PADDING = 10;

    /** A tab's picture, before its label, and the space after it. */
    public static final int ICON_SIZE = 20;
    public static final int ICON_GAP = 8;

    /** The selected tab's accent bar. */
    public static final int MARKER_WIDTH = 3;

    /** A tab label: 14, uppercase, shrinking to fit but never under 12. A section heading: 13, bold, uppercase. */
    public static final int LABEL_SIZE = 14;
    public static final int LABEL_MIN_SIZE = 12;
    public static final int SECTION_LABEL_SIZE = 13;

    /**
     * The longest rail label, in characters once uppercased (the rail draws it in capitals), that stays on one
     * line: the label has {@code RAIL_WIDTH - 2 * RAIL_PADDING - 4 (the list's scroll gutter) - 2 * ROW_PADDING
     * - ICON_SIZE - ICON_GAP} = 182px, and a capital at the 12px shrink floor is about 8px.
     */
    public static final int RAIL_LABEL_MAX_CHARS = 22;

    /** The type scale: no player text in the frame or rail is smaller than {@link #FONT_FLOOR}. */
    public static final int FONT_FLOOR = 12;
    public static final int CAPTION_SIZE = 12;
    public static final int BODY_SIZE = 14;
    public static final int HEADING_SIZE = 16;
    public static final int TITLE_SIZE = 20;

    private MenuFrame() {
    }
}
