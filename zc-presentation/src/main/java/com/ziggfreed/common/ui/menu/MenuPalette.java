package com.ziggfreed.common.ui.menu;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.theme.Palette;

/**
 * The shared menu's colours and textures as ONE {@link Palette}, the library's theme palette (the same
 * fields a consumer's theme carries), and the library's default palette. The frame and the rail read every
 * colour and texture from the palette in force ({@link MenuDeps#palette()}); a slot a palette leaves unset or
 * malformed falls back to the default's, so a three-colour palette still paints a whole rail.
 *
 * <p>The rail reads {@code background} (a tab's resting fill), {@code header} (the selected tab's fill, and a
 * hovered one), {@code textMuted} (a tab label, a heading, a caption), {@code textPrimary} (the selected
 * label, a title, the server name) and {@code accent} (the selected tab's marker). The frame
 * ({@link MenuFrameRetint}) reads {@code frame} (the frame body's tint), {@code primary} (the rail's and a
 * page panel's tint) and {@code textureDir} with {@code frameBorder} and {@code panelBorder} (a bespoke
 * texture set). The default leaves the frame slots unset, so the frame keeps its authored textures, and its
 * five colours are the ones {@code ZigFrames.ui} and {@code Pages/ZigMenuTab.ui} spell
 * ({@code ZigMenuDocumentsTest}); {@code MenuPaletteTest} holds them to 4.5:1 (3:1 for the marker, a shape).
 */
public final class MenuPalette {

    public static final String BACKGROUND = "#0a1119";
    public static final String HEADER = "#1a2633";
    public static final String TEXT_MUTED = "#b6c9de";
    public static final String TEXT_PRIMARY = "#ffffff";
    public static final String ACCENT = "#ffd24a";

    /** Normal text (WCAG AA). */
    public static final double TEXT_FLOOR = 4.5;

    /** Large text (18px, or bold 14px and up) and a non-text shape. */
    public static final double LARGE_FLOOR = 3.0;

    /** The rail's five colours, read from a palette with the default filling any gap. */
    public record Resolved(@Nonnull String background, @Nonnull String header, @Nonnull String textMuted,
                           @Nonnull String textPrimary, @Nonnull String accent) {
    }

    /** One colour on one fill, and the ratio it may not fall under. */
    public record Pair(@Nonnull String what, @Nonnull String foreground, @Nonnull String background, double floor) {
    }

    private static final Resolved DEFAULT = new Resolved(BACKGROUND, HEADER, TEXT_MUTED, TEXT_PRIMARY, ACCENT);

    private MenuPalette() {
    }

    /** A fresh copy of the library's default palette (a {@link Palette} is mutable, so never shared). */
    @Nonnull
    public static Palette defaults() {
        Palette palette = new Palette(null, ACCENT, BACKGROUND);
        palette.header = HEADER;
        palette.textMuted = TEXT_MUTED;
        palette.textPrimary = TEXT_PRIMARY;
        return palette;
    }

    /** The rail's colours from {@code palette}, slot by slot, the default's wherever it says nothing usable. */
    @Nonnull
    public static Resolved resolve(@Nullable Palette palette) {
        if (palette == null) {
            return DEFAULT;
        }
        return new Resolved(slot(palette.background, BACKGROUND), slot(palette.header, HEADER),
                slot(palette.textMuted, TEXT_MUTED), slot(palette.textPrimary, TEXT_PRIMARY),
                slot(palette.accent, ACCENT));
    }

    /** The pairs a readable rail keeps, for any resolved palette. */
    @Nonnull
    public static List<Pair> pairs(@Nonnull Resolved colours) {
        return List.of(
                new Pair("a tab label, a heading or a caption", colours.textMuted(), colours.background(), TEXT_FLOOR),
                new Pair("a hovered tab label", colours.textMuted(), colours.header(), TEXT_FLOOR),
                new Pair("the selected tab label", colours.textPrimary(), colours.header(), TEXT_FLOOR),
                new Pair("a title or the server name", colours.textPrimary(), colours.background(), TEXT_FLOOR),
                new Pair("the selected tab's marker", colours.accent(), colours.header(), LARGE_FLOOR));
    }

    /** The default's five colours, every one a document in the frame or rail may spell. */
    @Nonnull
    public static List<String> defaultColours() {
        return List.of(BACKGROUND, HEADER, TEXT_MUTED, TEXT_PRIMARY, ACCENT);
    }

    /** WCAG's contrast ratio between two {@code #rrggbb} colours, 1 to 21. */
    public static double contrast(@Nonnull String a, @Nonnull String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG's relative luminance of a {@code #rrggbb} colour. */
    static double luminance(@Nonnull String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xff) + 0.7152 * channel((rgb >> 8) & 0xff)
                + 0.0722 * channel(rgb & 0xff);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    @Nonnull
    private static String slot(@Nullable String value, @Nonnull String fallback) {
        return UiRetint.isSixDigitHex(value) ? value : fallback;
    }
}
