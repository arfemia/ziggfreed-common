package com.ziggfreed.common.ui.hud.statbar;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.kit.ZigTokens;

/**
 * The stat bars' geometry, pure: where a bar's slot box sits beside another HUD or on its own, how long its fill is,
 * and what colour it is painted. MIRRORS {@code Hud/ZigStatBars.ui}'s GEOMETRY header: every constant below is a
 * number written there, and the two move together ({@code ZigStatBarsDocumentTest} holds them).
 *
 * <p>A slot is a box pinned exactly as the HUD it sits beside is (the same corner, offsets and width, so it lines up
 * with that HUD's left edge on any window), stretched past that HUD on the bar's side by the gap and the bars already
 * stacked there; the bar sits at the box's far end, pushed there by a lead spacer when it is below. A centred HUD
 * stretches its box both ways, so the two stay centred alike whatever the window's height.
 */
public final class StatBarLayout {

    /** How many bars one player's screen can show at once: the document's slots. */
    public static final int SLOTS = 4;

    /** A bar's height: its line and the vertical padding around it. */
    public static final int BAR_HEIGHT = 20;

    /** The narrowest a bar is drawn, so its name, its track and its figure all fit. */
    public static final int MIN_WIDTH = 160;

    /** The padding at each end of the bar. */
    static final int PAD_X = 6;

    /** The name's box at the bar's left end. */
    static final int LABEL_WIDTH = 60;

    /** The figure's box at the bar's right end. */
    static final int FIGURE_WIDTH = 36;

    /** The space between the name and the track, and between the track and the figure. */
    static final int TRACK_GAP = 6;

    /** The track's padding around its fill. */
    static final int TRACK_PAD = 1;

    /** Where a standing bar's unwritten leaves fall: low and centred, over the hotbar. */
    static final HudPosition STANDING_FALLBACK =
            new HudPosition(HudPosition.AnchorEdge.BOTTOM, HudPosition.HorizontalEdge.CENTER, 0, 120);

    private StatBarLayout() {
    }

    /**
     * One bar's slot: the box (pinned by {@code edge}, {@code horizontal} and the offsets, {@code width} by
     * {@code height}), the {@code lead} above the bar inside it, and the bar's own width. Values only, so two compare.
     */
    public record Box(@Nonnull HudPosition.AnchorEdge edge, @Nonnull HudPosition.HorizontalEdge horizontal,
            int offsetX, int offsetY, int width, int height, int lead, int barWidth) {

        /** The box's anchor, one edge pinned per axis. */
        @Nonnull
        public Anchor anchor() {
            return new HudPosition(edge, horizontal, offsetX, offsetY).toAnchor(width, height);
        }

        /** The lead spacer's anchor: its height alone. */
        @Nonnull
        public Anchor leadAnchor() {
            Anchor a = new Anchor();
            a.setHeight(Value.of(lead));
            return a;
        }

        /** The bar's anchor: its width and the bar's height. */
        @Nonnull
        public Anchor barAnchor() {
            Anchor a = new Anchor();
            a.setWidth(Value.of(barWidth));
            a.setHeight(Value.of(BAR_HEIGHT));
            return a;
        }
    }

    /** A bar standing on its own at {@code position}, {@code width} wide (held to {@link #MIN_WIDTH}). */
    @Nonnull
    public static Box standing(@Nonnull HudPosition position, int width) {
        int w = Math.max(MIN_WIDTH, width);
        return new Box(position.getAnchorEdge(), position.getHorizontalEdge(), position.getOffsetX(),
                position.getOffsetY(), w, BAR_HEIGHT, 0, w);
    }

    /**
     * A bar beside a HUD sitting at {@code host}, {@code hostWidth} by {@code hostHeight}, of which {@code contentWidth}
     * shows from its left edge: on its {@code below} side, {@code gap} from it, past {@code stacked} bars already on
     * that side. The bar is as wide as what the HUD shows (never under {@link #MIN_WIDTH}, never past the HUD's box). A
     * side with no room on screen (above a HUD pinned near the top, below one pinned near the bottom) turns to the
     * other side.
     */
    @Nonnull
    public static Box beside(@Nonnull HudPosition host, int hostWidth, int hostHeight, int contentWidth,
            boolean below, int gap, int stacked) {
        int g = Math.max(0, gap);
        int offset = g + Math.max(0, stacked) * (g + BAR_HEIGHT);
        int extend = offset + BAR_HEIGHT;
        int width = Math.max(1, hostWidth);
        int barWidth = Math.min(width, Math.max(MIN_WIDTH, contentWidth));
        HudPosition.AnchorEdge edge = host.getAnchorEdge();
        int x = host.getOffsetX();
        int y = host.getOffsetY();
        boolean under = below;
        if (edge == HudPosition.AnchorEdge.TOP && !under && y - extend < 0) {
            under = true;
        } else if (edge == HudPosition.AnchorEdge.BOTTOM && under && y - extend < 0) {
            under = false;
        }
        return switch (edge) {
            case TOP -> under
                    ? new Box(edge, host.getHorizontalEdge(), x, y, width, hostHeight + extend,
                            hostHeight + offset, barWidth)
                    : new Box(edge, host.getHorizontalEdge(), x, y - extend, width, hostHeight + extend, 0, barWidth);
            case BOTTOM -> under
                    ? new Box(edge, host.getHorizontalEdge(), x, y - extend, width, hostHeight + extend,
                            hostHeight + offset, barWidth)
                    : new Box(edge, host.getHorizontalEdge(), x, y, width, hostHeight + extend, 0, barWidth);
            case CENTER -> under
                    ? new Box(edge, host.getHorizontalEdge(), x, y, width, hostHeight + 2 * extend,
                            extend + hostHeight + offset, barWidth)
                    : new Box(edge, host.getHorizontalEdge(), x, y, width, hostHeight + 2 * extend, 0, barWidth);
        };
    }

    /** The fill's width on a bar {@code barWidth} wide that is {@code fraction} full (0 to 1). */
    public static int fillWidth(int barWidth, double fraction) {
        int track = trackWidth(barWidth);
        double f = Double.isFinite(fraction) ? Math.max(0.0, Math.min(1.0, fraction)) : 0.0;
        return (int) Math.round(track * f);
    }

    /** The room the fill has on a bar {@code barWidth} wide: what the name, the figure, the gaps and pads leave. */
    public static int trackWidth(int barWidth) {
        return Math.max(0, barWidth - 2 * PAD_X - LABEL_WIDTH - FIGURE_WIDTH - 2 * TRACK_GAP - 2 * TRACK_PAD);
    }

    /** The fill's anchor at {@code width}: pinned to the track's left, as tall as the track. */
    @Nonnull
    public static Anchor fillAnchor(int width) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(0));
        a.setTop(Value.of(0));
        a.setBottom(Value.of(0));
        a.setWidth(Value.of(Math.max(0, width)));
        return a;
    }

    /** The whole number a bar shows for a stat at {@code value}: rounded down, never below 0. */
    public static long figure(double value) {
        return Double.isFinite(value) ? Math.max(0L, (long) Math.floor(value + 1e-6)) : 0L;
    }

    /**
     * The fill colour {@code authored} names, as Java pushes it ({@code #rrggbb}, or {@code #rrggbbaa} for a kit colour
     * with a transparency): six or eight hex digits as written, else a kit colour by its name (with or without a
     * leading {@code @}, any case), else null for the caller's fallback.
     */
    @Nullable
    public static String colour(@Nullable String authored) {
        if (authored == null || authored.isBlank()) {
            return null;
        }
        String trimmed = authored.trim();
        if (UiRetint.isHex(trimmed)) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        String name = trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
        for (var token : ZigTokens.colours().entrySet()) {
            if (token.getKey().equalsIgnoreCase(name)) {
                return UiRetint.fromMarkup(token.getValue());
            }
        }
        return null;
    }

    /** The colour a bar is painted when its own reads as none: the kit's active fill. */
    @Nonnull
    public static String fallbackColour() {
        return ZigTokens.TONE_ACTIVE_FILL;
    }
}
