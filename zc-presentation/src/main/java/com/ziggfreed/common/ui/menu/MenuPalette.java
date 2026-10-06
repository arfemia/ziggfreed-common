package com.ziggfreed.common.ui.menu;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.theme.Palette;

/**
 * The shared menu's colours as ONE {@link Palette}, the library's theme palette (the same fields a consumer's
 * theme carries), and the library's default palette, whose every colour is vanilla's own left rail
 * ({@code Common.ui}, {@code Pages/WorldEvent/WorldEventNavButton.ui}). The frame and the rail read every colour
 * and texture from the palette in force ({@link MenuDeps#palette()}); a slot a palette leaves unset or malformed
 * falls back to the default's, so a three-colour palette still paints a whole rail.
 *
 * <p>The rail reads {@code background} (the rail's pane), {@code header} (a hovered tab's fill and the selected
 * tab's), {@code textMuted} (a tab label, a section heading, a caption), {@code textPrimary} (the selected label
 * under its gold mask, the server name), {@code accent} (the selected tab's bar) and {@code divider} (the rule
 * between the consumer's section and the library's tabs). The three fills may carry an alpha
 * ({@code #rrggbbaa}); text and the bar are opaque ({@code #rrggbb}). The frame ({@link MenuFrameRetint}) reads
 * {@code frame}, {@code primary} and the texture set, which the default leaves unset.
 *
 * <p>A document spells an alpha colour vanilla's way, {@code #rrggbb(a)}, and Java {@code #rrggbbaa}
 * ({@link #fromMarkup} is the one conversion); {@code ZigMenuDocumentsTest} holds {@code ZigFrames.ui} and
 * {@code Pages/ZigMenuTab.ui} to {@link #defaultColours()}. {@code MenuPaletteTest} holds the default to 4.5:1
 * (3:1 for the bar), judging a translucent fill over what it covers: the pane over the frame body
 * ({@link #BACKDROP}), a tab's fill over the pane.
 */
public final class MenuPalette {

    /** The rail's pane: vanilla's {@code #000000(0.15)}. */
    public static final String BACKGROUND = "#00000026";

    /** A hovered tab's fill and the selected tab's: {@code Common.ui}'s {@code @ColorSimpleButtonBackground}, {@code #000000(0.2)}. */
    public static final String HEADER = "#00000033";

    /** A tab label, a section heading, a caption: {@code Common.ui}'s {@code @ColorDefaultLabel}. */
    public static final String TEXT_MUTED = "#96a9be";

    /** The selected label (under the gold gradient mask), the server name: {@code Common.ui}'s {@code @ColorDefault}. */
    public static final String TEXT_PRIMARY = "#ffffff";

    /** The selected tab's bar: {@code Common.ui}'s {@code @ColorGoldHighlight} (vanilla's rows have no bar). */
    public static final String ACCENT = "#e8a93b";

    /** The rule between the consumer's section and the library's tabs: {@code Common.ui}'s {@code @ContentSeparator}. */
    public static final String DIVIDER = "#2b3542";

    /** No fill: a resting tab's {@code Default} state. Not a palette slot, so no theme recolours it. */
    public static final String CLEAR = "#00000000";

    /** The frame body under the rail: every pixel inside {@code Common/ContainerFullPatch@2x.png}'s 20px border. */
    static final String BACKDROP = "#16212f";

    /** {@code Common/TextGradient.png}'s darkest stop, the shade the selected label's mask brings white down to. */
    static final String MASK_DARKEST = "#e8b962";

    /** Normal text (WCAG AA). */
    public static final double TEXT_FLOOR = 4.5;

    /** Large text (18px, or bold 14px and up) and a non-text shape. */
    public static final double LARGE_FLOOR = 3.0;

    /** The rail's six colours, read from a palette with the default filling any gap. */
    public record Resolved(@Nonnull String background, @Nonnull String header, @Nonnull String textMuted,
                           @Nonnull String textPrimary, @Nonnull String accent, @Nonnull String divider) {
    }

    /** One colour on one fill, and the ratio it may not fall under. */
    public record Pair(@Nonnull String what, @Nonnull String foreground, @Nonnull String background, double floor) {
    }

    private static final Resolved DEFAULT = new Resolved(BACKGROUND, HEADER, TEXT_MUTED, TEXT_PRIMARY, ACCENT, DIVIDER);

    private MenuPalette() {
    }

    /** A fresh copy of the library's default palette (a {@link Palette} is mutable, so never shared). */
    @Nonnull
    public static Palette defaults() {
        Palette palette = new Palette(null, ACCENT, BACKGROUND);
        palette.header = HEADER;
        palette.textMuted = TEXT_MUTED;
        palette.textPrimary = TEXT_PRIMARY;
        palette.divider = DIVIDER;
        return palette;
    }

    /** The rail's colours from {@code palette}, slot by slot, the default's wherever it says nothing usable. */
    @Nonnull
    public static Resolved resolve(@Nullable Palette palette) {
        if (palette == null) {
            return DEFAULT;
        }
        return new Resolved(fill(palette.background, BACKGROUND), fill(palette.header, HEADER),
                opaque(palette.textMuted, TEXT_MUTED), opaque(palette.textPrimary, TEXT_PRIMARY),
                opaque(palette.accent, ACCENT), fill(palette.divider, DIVIDER));
    }

    /**
     * The pairs a readable rail keeps, for any resolved palette. A translucent fill is judged over what it
     * covers (the pane over the frame body, a tab's fill over the pane), and the selected label as
     * {@code textPrimary} under the mask's darkest stop. The rule is decoration, so it has no floor.
     */
    @Nonnull
    public static List<Pair> pairs(@Nonnull Resolved colours) {
        String pane = over(colours.background(), BACKDROP);
        String tabFill = over(colours.header(), pane);
        String selectedLabel = multiply(colours.textPrimary(), MASK_DARKEST);
        return List.of(
                new Pair("a tab label, a heading or a caption", colours.textMuted(), pane, TEXT_FLOOR),
                new Pair("a hovered tab label", colours.textMuted(), tabFill, TEXT_FLOOR),
                new Pair("the selected tab label, under its mask", selectedLabel, tabFill, TEXT_FLOOR),
                new Pair("the server name", colours.textPrimary(), pane, TEXT_FLOOR),
                new Pair("the selected tab's bar", colours.accent(), tabFill, LARGE_FLOOR));
    }

    /** The default's colours, every one a document in the frame or rail may spell, plus {@link #CLEAR}. */
    @Nonnull
    public static List<String> defaultColours() {
        return List.of(BACKGROUND, HEADER, TEXT_MUTED, TEXT_PRIMARY, ACCENT, DIVIDER, CLEAR);
    }

    /**
     * The one conversion between a colour's two spellings: a document spells an alpha colour vanilla's way,
     * {@code #rrggbb(a)} with {@code a} from 0 to 1, and Java {@code #rrggbbaa}, the last two digits being
     * {@code round(a x 255)} in hex (the form {@link UiRetint#isHex} takes); an opaque {@code #rrggbb} is
     * itself. Lower-cased, so the two compare.
     */
    @Nonnull
    public static String fromMarkup(@Nonnull String spelled) {
        String hex = spelled.substring(0, 7).toLowerCase(Locale.ROOT);
        int open = spelled.indexOf('(');
        if (open < 0) {
            return hex;
        }
        double alpha = Double.parseDouble(spelled.substring(open + 1, spelled.indexOf(')', open)));
        return hex + String.format(Locale.ROOT, "%02x", (int) Math.round(alpha * 255));
    }

    /** WCAG's contrast ratio between two {@code #rrggbb} colours, 1 to 21. */
    public static double contrast(@Nonnull String a, @Nonnull String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG's relative luminance of a {@code #rrggbb} colour. */
    static double luminance(@Nonnull String hex) {
        int rgb = Integer.parseInt(hex.substring(1, 7), 16);
        return 0.2126 * channel((rgb >> 16) & 0xff) + 0.7152 * channel((rgb >> 8) & 0xff)
                + 0.0722 * channel(rgb & 0xff);
    }

    /** {@code colour} as seen over the opaque {@code under}: itself when opaque, else blended by its alpha. */
    @Nonnull
    static String over(@Nonnull String colour, @Nonnull String under) {
        if (colour.length() == 7) {
            return colour.toLowerCase(Locale.ROOT);
        }
        int fg = Integer.parseInt(colour.substring(1, 7), 16);
        double alpha = Integer.parseInt(colour.substring(7, 9), 16) / 255.0;
        int bg = Integer.parseInt(under.substring(1, 7), 16);
        return hex(blend(fg >> 16, bg >> 16, alpha), blend(fg >> 8, bg >> 8, alpha), blend(fg, bg, alpha));
    }

    /** Two opaque colours multiplied channel by channel: a label under a colour mask. */
    @Nonnull
    static String multiply(@Nonnull String a, @Nonnull String b) {
        int x = Integer.parseInt(a.substring(1, 7), 16);
        int y = Integer.parseInt(b.substring(1, 7), 16);
        return hex(product(x >> 16, y >> 16), product(x >> 8, y >> 8), product(x, y));
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int blend(int fg, int bg, double alpha) {
        return (int) Math.round(alpha * (fg & 0xff) + (1 - alpha) * (bg & 0xff));
    }

    private static int product(int a, int b) {
        return (int) Math.round((a & 0xff) * (b & 0xff) / 255.0);
    }

    @Nonnull
    private static String hex(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    /** A fill slot: {@code #rrggbb} or {@code #rrggbbaa}. */
    @Nonnull
    private static String fill(@Nullable String value, @Nonnull String fallback) {
        return UiRetint.isHex(value) ? value : fallback;
    }

    /** A text or bar slot: {@code #rrggbb} only. */
    @Nonnull
    private static String opaque(@Nullable String value, @Nonnull String fallback) {
        return UiRetint.isSixDigitHex(value) ? value : fallback;
    }
}
