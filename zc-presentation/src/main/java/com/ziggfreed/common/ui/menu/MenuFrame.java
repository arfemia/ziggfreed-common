package com.ziggfreed.common.ui.menu;

/**
 * The shared menu frame's one set of numbers. {@code ZigFrames.ui}'s {@code @ZigMenuFrame} spells the
 * same values (a document cannot read a Java constant), and {@code ZigMenuDocumentsTest} holds the two
 * together; a page on the frame derives its own widths from {@link #BODY_WIDTH}, never a literal.
 */
public final class MenuFrame {

    /** The frame's one size, for every page on the rail. */
    public static final int FRAME_WIDTH = 1570;
    public static final int FRAME_HEIGHT = 1000;

    /** The frame's {@code #Content} padding, all four sides. */
    public static final int FRAME_PADDING = 12;

    /** The rail column, {@link #RAIL}. */
    public static final int RAIL_WIDTH = 250;

    /** What a page's own columns share, beside the rail. */
    public static final int BODY_WIDTH = FRAME_WIDTH - 2 * FRAME_PADDING - RAIL_WIDTH;

    /** The rail host: a themeable panel (it carries a {@code Background}), holding the branding hosts and the list. */
    public static final String RAIL = "#MenuRail";

    /** The list the rail's rows are appended into, by index. */
    public static final String LIST = "#MenuList";

    /** The longest rail label, in characters, that stays on one line at the rail's width (body size). */
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
