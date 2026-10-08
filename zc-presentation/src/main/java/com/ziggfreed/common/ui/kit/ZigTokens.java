package com.ziggfreed.common.ui.kit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.menu.MenuPalette;

/**
 * The kit's named values in Java, the mirror of {@code Common/ZigTokens.ui}: one constant per colour token and per
 * named integer, for the few values Java pushes (a tone's accent bar, a clamped season accent, a fallback leaf when a
 * style cannot be swapped by reference). A document reads its colours from {@code ZigTokens.ui}
 * ({@code $ZK.@ZigInkBody}), never from here, and {@code ZigTokensDocumentTest} holds the two together through
 * {@link #colours()} and {@link #integers()}, keyed by the document's own names.
 *
 * <p>A document spells an alpha colour vanilla's way, {@code #rrggbb(a)}; the constants here are Java's
 * {@code #rrggbbaa} ({@link MenuPalette#fromMarkup}, the one conversion), and {@link #colours()} keeps the markup
 * spelling so the two compare as written.
 *
 * <p>The tones (accent fills and state-word colours) are semantic and never themed; {@code ZigTokensContrastTest}
 * holds every ink and tone text at 4.5:1 and every tone fill at 3:1 over the row, judged over {@link #FRAME_BODY}.
 * Two fills were lifted inside their hue to pass (Waiting {@code #6f5a8e} to {@code #7a64a0}, Blocked
 * {@code #4a5a6a} to {@code #5c6e80}).
 */
public final class ZigTokens {

    private static final Map<String, String> COLOURS = new LinkedHashMap<>();
    private static final Map<String, Integer> INTEGERS = new LinkedHashMap<>();

    // Inks: text colours, brightest first.
    /** {@code @ColorDefault}: text on the selected row and on the hero. */
    public static final String INK_BRIGHT = colour("ZigInkBright", "#ffffff");
    /** Vanilla row and page titles. */
    public static final String INK_STRONG = colour("ZigInkStrong", "#d6e4ee");
    /** Body text. */
    public static final String INK_BODY = colour("ZigInkBody", "#b6c9de");
    /** {@code @ColorDefaultLabel}: meta and sub lines, captions, hints. */
    public static final String INK_MUTED = colour("ZigInkMuted", "#96a9be");
    /** Vanilla's sub line: a finished row's meta; the darkest player text allowed. */
    public static final String INK_FAINT = colour("ZigInkFaint", "#7f93a6");
    /** The uppercase section label's steel. */
    public static final String INK_SECTION = colour("ZigInkSection", "#8fb4dc");
    /** Vanilla's section label grey ({@code @ZigCardHeaderStyle}). */
    public static final String INK_HEADING = colour("ZigInkHeading", "#9aacbc");

    // Surfaces.
    /** Vanilla's pane, {@code #000000(0.15)}. */
    public static final String SURFACE_PANE = colour("ZigSurfacePane", "#000000(0.15)");
    /** A row, card or tile at rest ({@code WorldEventListRow.ui}). */
    public static final String SURFACE_ROW = colour("ZigSurfaceRow", "#101925(0.55)");
    /** A hovered row. */
    public static final String SURFACE_ROW_HOVER = colour("ZigSurfaceRowHover", "#132033(0.8)");
    /** A pressed row. */
    public static final String SURFACE_ROW_PRESSED = colour("ZigSurfaceRowPressed", "#182a40(0.9)");
    /** The selected row, vanilla's {@code @SelectedRowStyle} steel blue, with {@link #INK_BRIGHT} on it. */
    public static final String SURFACE_ROW_SELECTED = colour("ZigSurfaceRowSelected", "#4274a5");
    /** A pill, the hero's text block. */
    public static final String SURFACE_SCRIM = colour("ZigSurfaceScrim", "#0a1119(0.8)");
    /** {@code Common.ui}'s {@code @ContentSeparator}. */
    public static final String DIVIDER = colour("ZigDivider", "#2b3542");
    /** {@code @ColorGoldHighlight}: points and Collect only. */
    public static final String ACCENT = colour("ZigAccent", "#e8a93b");

    // Tones: an accent fill (bar, dot, pill) and a text colour (the state word) per meaning.
    public static final String TONE_ACTIVE_FILL = colour("ZigToneActiveFill", "#4274a5");
    public static final String TONE_ACTIVE_TEXT = colour("ZigToneActiveText", "#7a9cc6");
    public static final String TONE_COLLECT_FILL = colour("ZigToneCollectFill", "#e8a93b");
    public static final String TONE_COLLECT_TEXT = colour("ZigToneCollectText", "#e8a93b");
    public static final String TONE_DONE_FILL = colour("ZigToneDoneFill", "#4f9e63");
    public static final String TONE_DONE_TEXT = colour("ZigToneDoneText", "#7fc893");
    public static final String TONE_LIVE_FILL = colour("ZigToneLiveFill", "#4f9e63");
    public static final String TONE_LIVE_TEXT = colour("ZigToneLiveText", "#7fc893");
    public static final String TONE_AVAILABLE_FILL = colour("ZigToneAvailableFill", "#c08a3a");
    public static final String TONE_AVAILABLE_TEXT = colour("ZigToneAvailableText", "#d9a75e");
    public static final String TONE_WAITING_FILL = colour("ZigToneWaitingFill", "#7a64a0");
    public static final String TONE_WAITING_TEXT = colour("ZigToneWaitingText", "#b3a0d1");
    public static final String TONE_BLOCKED_FILL = colour("ZigToneBlockedFill", "#5c6e80");
    public static final String TONE_BLOCKED_TEXT = colour("ZigToneBlockedText", "#96a9be");
    public static final String TONE_DANGER_FILL = colour("ZigToneDangerFill", "#b04a4a");
    public static final String TONE_DANGER_TEXT = colour("ZigToneDangerText", "#e08080");

    // Spacing, heights and picture rungs.
    public static final int SPACE_1 = integer("ZigSpace1", 4);
    public static final int SPACE_2 = integer("ZigSpace2", 8);
    public static final int SPACE_3 = integer("ZigSpace3", 12);
    public static final int SPACE_4 = integer("ZigSpace4", 16);
    public static final int SPACE_5 = integer("ZigSpace5", 24);
    public static final int SPACE_6 = integer("ZigSpace6", 32);
    /**
     * {@code Pages/ZigLedgerRow.ui} with a one-line title, vanilla's World Event row; a title on two lines grows it
     * ({@link RowSize}).
     */
    public static final int ROW_HEIGHT = integer("ZigRowHeight", 56);
    /** {@code Pages/ZigLedgerRowCompact.ui}, its least height ({@link RowSize}). */
    public static final int COMPACT_ROW_HEIGHT = integer("ZigCompactRowHeight", 44);
    /**
     * {@code Pages/ZigLedgerRowTall.ui} with a one-line title: the standard row plus its meta's second line; a title on
     * two lines grows it ({@link RowSize}).
     */
    public static final int TALL_ROW_HEIGHT = integer("ZigTallRowHeight", 74);
    public static final int SECTION_HEAD_HEIGHT = integer("ZigSectionHeadHeight", 32);
    /** {@code Pages/ZigDetailLine.ui}. */
    public static final int LINE_HEIGHT = integer("ZigLineHeight", 32);
    /** Segments, dropdowns, the search row (vanilla {@code @SmallButtonHeight}). */
    public static final int CONTROL_HEIGHT = integer("ZigControlHeight", 32);
    /** Action-bar buttons. */
    public static final int ACTION_HEIGHT = integer("ZigActionHeight", 40);
    /** A page's header band. */
    public static final int HEADER_HEIGHT = integer("ZigHeaderHeight", 60);
    public static final int PILL_HEIGHT = integer("ZigPillHeight", 24);
    public static final int PIC_INLINE = integer("ZigPicInline", 20);
    public static final int PIC_LINE = integer("ZigPicLine", 28);
    public static final int PIC_ROW = integer("ZigPicRow", 32);
    public static final int PIC_TILE = integer("ZigPicTile", 40);
    public static final int PIC_HEADER = integer("ZigPicHeader", 48);
    /** The native size of every item icon, so nothing item-based draws larger. */
    public static final int PIC_LARGE = integer("ZigPicLarge", 64);

    /**
     * The frame body every surface is laid over (every pixel inside {@code Common/ContainerFullPatch@2x.png}'s
     * border, the same colour {@code MenuPaletteTest} judges the rail over). Not a document token.
     */
    public static final String FRAME_BODY = "#16212f";

    /**
     * Dark ink for a word on a light data fill (a gold or pale season pill, where white does not read): the scrim's
     * colour, opaque. Not a document token; {@link #inkOn} chooses it.
     */
    public static final String INK_DARK = "#0a1119";

    /** Normal text (WCAG AA). */
    public static final double TEXT_FLOOR = 4.5;

    /** A non-text mark: an accent bar, a dot, a strip. */
    public static final double MARK_FLOOR = 3.0;

    private ZigTokens() {
    }

    /** Every colour token by its document name (no {@code @}), in the document's markup spelling. */
    @Nonnull
    public static Map<String, String> colours() {
        return Collections.unmodifiableMap(COLOURS);
    }

    /** Every named integer by its document name (no {@code @}). */
    @Nonnull
    public static Map<String, Integer> integers() {
        return Collections.unmodifiableMap(INTEGERS);
    }

    /**
     * {@code colour} as it reads laid over the opaque {@code under}: itself when opaque ({@code #rrggbb}), else
     * blended by the alpha in its last two digits ({@code #rrggbbaa}). Lower-cased {@code #rrggbb}.
     */
    @Nonnull
    public static String seenOver(@Nonnull String colour, @Nonnull String under) {
        if (colour.length() == 7) {
            return colour.toLowerCase(Locale.ROOT);
        }
        int fg = Integer.parseInt(colour.substring(1, 7), 16);
        double alpha = Integer.parseInt(colour.substring(7, 9), 16) / 255.0;
        int bg = Integer.parseInt(under.substring(1, 7), 16);
        return String.format(Locale.ROOT, "#%02x%02x%02x", blend(fg >> 16, bg >> 16, alpha),
                blend(fg >> 8, bg >> 8, alpha), blend(fg, bg, alpha));
    }

    /** The row's resting colour as it reads over the frame body: the surface every accent is judged on. */
    @Nonnull
    public static String rowSeen() {
        return seenOver(SURFACE_ROW, FRAME_BODY);
    }

    /**
     * An authored data accent (a season's, a category's) clamped for painting: the colour itself when it is a
     * {@code #rrggbb} that keeps {@link #MARK_FLOOR} against the row, else {@link #ACCENT}.
     */
    @Nonnull
    public static String clampAccent(@Nullable String authoredHex) {
        if (authoredHex == null || !UiRetint.isSixDigitHex(authoredHex)) {
            return ACCENT;
        }
        String hex = authoredHex.toLowerCase(Locale.ROOT);
        return MenuPalette.contrast(hex, rowSeen()) >= MARK_FLOOR ? hex : ACCENT;
    }

    /** The ink a word reads best in on the opaque {@code fill}: {@link #INK_BRIGHT}, or {@link #INK_DARK} on a light fill. */
    @Nonnull
    public static String inkOn(@Nonnull String fill) {
        return MenuPalette.contrast(INK_BRIGHT, fill) >= MenuPalette.contrast(INK_DARK, fill) ? INK_BRIGHT : INK_DARK;
    }

    private static int blend(int fg, int bg, double alpha) {
        return (int) Math.round(alpha * (fg & 0xff) + (1 - alpha) * (bg & 0xff));
    }

    @Nonnull
    private static String colour(@Nonnull String name, @Nonnull String markup) {
        COLOURS.put(name, markup);
        return MenuPalette.fromMarkup(markup);
    }

    private static int integer(@Nonnull String name, int value) {
        INTEGERS.put(name, value);
        return value;
    }
}
