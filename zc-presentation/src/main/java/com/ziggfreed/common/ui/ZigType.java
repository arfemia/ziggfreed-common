package com.ziggfreed.common.ui;

/**
 * The family's type scale for the few text sizes Java sets (a {@code .Style.FontSize} push, an inline element
 * appended from Java): the same numbers {@code Common/ZigType.ui} names for every document, held together by
 * {@code ZigTypeTest}. A document reads its sizes from that file ({@code FontSize: $ZT.@ZigFontBody}), never from
 * here.
 *
 * <p>The maintainer's readability ruling (2026-10-06): no player text under {@link #FLOOR}; descriptions,
 * captions and hints at {@link #CAPTION}; row labels and body text at {@link #BODY}; headings and titles keep
 * their sizes.
 */
public final class ZigType {

    /** Captions, descriptions, hints, counts, a row's second line: the floor. */
    public static final int CAPTION = 13;

    /** An uppercase section label, vanilla's own size for one. */
    public static final int SECTION = 13;

    /** Row labels, body text, buttons, fields, chips. */
    public static final int BODY = 14;

    /** A name that leads its row or card. */
    public static final int EMPHASIS = 15;

    /** A heading inside a page. */
    public static final int HEADING = 16;

    /** A large heading or a sub-title. */
    public static final int SUBTITLE = 18;

    /** A page title. */
    public static final int TITLE = 20;

    /** A big number. */
    public static final int DISPLAY = 22;
    public static final int DISPLAY_LARGE = 24;

    /** A hero title over full-width art (the Almanac's season name), the largest step. */
    public static final int HERO = 28;

    /**
     * The one exception under {@link #FLOOR}, by the maintainer's ruling: a label inside a HUD box too small for
     * the floor. The family's {@code RepoHygieneTest} allows it in those documents only.
     */
    public static final int MICRO = 11;

    /** No player text is drawn smaller, shrink-to-fit included, but for {@link #MICRO}'s few HUD labels. */
    public static final int FLOOR = CAPTION;

    private ZigType() {
    }
}
