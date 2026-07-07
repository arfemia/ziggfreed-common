package com.ziggfreed.common.ui.hud;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

/**
 * Mod-agnostic layout value for an in-world HUD overlay: a vertical edge ({@link AnchorEdge} TOP /
 * BOTTOM / CENTER) and a horizontal edge ({@link HorizontalEdge} LEFT / CENTER / RIGHT) plus pixel
 * offsets. {@link #toAnchor(int, int)} builds a Hytale {@link Anchor} that always sets {@code Width}/
 * {@code Height} and pins EXACTLY ONE edge per axis, so an offset moves the panel instead of being
 * neutralized by an opposing edge stretch.
 *
 * <p>Config-facing form: a named corner preset ({@code TOP_LEFT}, {@code TOP_CENTER}, ...,
 * {@code CENTER}, ..., {@code BOTTOM_RIGHT}) plus offsets, parsed by {@link #parse}. The offsets measure
 * from the pinned edge(s); for a {@code CENTER} axis they shift the centred element.
 *
 * <p>This value carries NO consumer-specific defaults - a consumer supplies its own default position
 * (e.g. a HUD's own {@code defaultPosition()}). Lifted into ziggfreed-common so every mod's HUD reads
 * ONE layout authority instead of a per-mod copy.
 */
public final class HudPosition {

    public enum AnchorEdge {
        TOP, BOTTOM, CENTER
    }

    public enum HorizontalEdge {
        LEFT, CENTER, RIGHT
    }

    private final AnchorEdge anchorEdge;
    private final HorizontalEdge horizontalEdge;
    private final int offsetX;
    private final int offsetY;

    public HudPosition(@Nonnull AnchorEdge anchorEdge, @Nonnull HorizontalEdge horizontalEdge,
            int offsetX, int offsetY) {
        this.anchorEdge = anchorEdge;
        this.horizontalEdge = horizontalEdge;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /**
     * Parse a named corner preset ({@code "TOP_LEFT"}, {@code "CENTER_RIGHT"}, {@code "BOTTOM_CENTER"},
     * plain {@code "CENTER"}, case-insensitive + whitespace-tolerant) + offsets into a
     * {@code HudPosition}; {@code null} for an unrecognized preset (the caller falls back to its default
     * and may warn).
     */
    @Nullable
    public static HudPosition parse(@Nullable String preset, int offsetX, int offsetY) {
        if (preset == null) {
            return null;
        }
        return switch (preset.trim().toUpperCase(Locale.ROOT)) {
            case "TOP_LEFT" -> new HudPosition(AnchorEdge.TOP, HorizontalEdge.LEFT, offsetX, offsetY);
            case "TOP_CENTER" -> new HudPosition(AnchorEdge.TOP, HorizontalEdge.CENTER, offsetX, offsetY);
            case "TOP_RIGHT" -> new HudPosition(AnchorEdge.TOP, HorizontalEdge.RIGHT, offsetX, offsetY);
            case "CENTER_LEFT" -> new HudPosition(AnchorEdge.CENTER, HorizontalEdge.LEFT, offsetX, offsetY);
            case "CENTER" -> new HudPosition(AnchorEdge.CENTER, HorizontalEdge.CENTER, offsetX, offsetY);
            case "CENTER_RIGHT" -> new HudPosition(AnchorEdge.CENTER, HorizontalEdge.RIGHT, offsetX, offsetY);
            case "BOTTOM_LEFT" -> new HudPosition(AnchorEdge.BOTTOM, HorizontalEdge.LEFT, offsetX, offsetY);
            case "BOTTOM_CENTER" -> new HudPosition(AnchorEdge.BOTTOM, HorizontalEdge.CENTER, offsetX, offsetY);
            case "BOTTOM_RIGHT" -> new HudPosition(AnchorEdge.BOTTOM, HorizontalEdge.RIGHT, offsetX, offsetY);
            default -> null;
        };
    }

    /** True when {@code preset} names a valid position (a command / config validates before applying). */
    public static boolean isValidPreset(@Nullable String preset) {
        return parse(preset, 0, 0) != null;
    }

    @Nonnull
    public AnchorEdge getAnchorEdge() {
        return anchorEdge;
    }

    @Nonnull
    public HorizontalEdge getHorizontalEdge() {
        return horizontalEdge;
    }

    public int getOffsetX() {
        return offsetX;
    }

    public int getOffsetY() {
        return offsetY;
    }

    /**
     * Build a Hytale {@link Anchor} sized to the supplied pixel dimensions and positioned according to
     * this {@code HudPosition}: {@code Width}/{@code Height} always set, exactly one horizontal and one
     * vertical edge pinned.
     */
    @Nonnull
    public Anchor toAnchor(int panelWidth, int panelHeight) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(panelWidth));
        anchor.setHeight(Value.of(panelHeight));
        applyEdges(anchor);
        return anchor;
    }

    /**
     * Like {@link #toAnchor(int, int)} but sets NO {@code Height}, so the anchored element sizes to its
     * own content (a {@code LayoutMode: Top} panel grows downward from the pinned vertical edge - the
     * native objective-HUD pattern). Width is still set (an edge offset needs it), and exactly one edge
     * is pinned per axis. For a TOP anchor the panel grows down, the only content-height layout verified
     * against the native HUD.
     */
    @Nonnull
    public Anchor toAnchorContentHeight(int panelWidth) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(panelWidth));
        applyEdges(anchor);
        return anchor;
    }

    /** Pin exactly one horizontal + one vertical edge (shared by the two {@code toAnchor*} forms). */
    private void applyEdges(@Nonnull Anchor anchor) {
        switch (horizontalEdge) {
            case LEFT -> anchor.setLeft(Value.of(offsetX));
            case RIGHT -> anchor.setRight(Value.of(offsetX));
            // NO Horizontal here: in Hytale's DSL "Horizontal: N" is a LEFT+RIGHT inset (stretch to fill
            // minus N), so "Horizontal: 0" would stretch the panel to FULL SCREEN width instead of
            // centering it. A fixed Width with no Horizontal centers by default; a nonzero offsetX still
            // nudges via Left so a CENTER preset with an offset is not silently ignored.
            case CENTER -> {
                if (offsetX != 0) {
                    anchor.setLeft(Value.of(offsetX));
                }
            }
        }

        switch (anchorEdge) {
            case TOP -> anchor.setTop(Value.of(offsetY));
            case BOTTOM -> anchor.setBottom(Value.of(offsetY));
            case CENTER -> anchor.setVertical(Value.of(offsetY));
        }
    }

    @Override
    public String toString() {
        return "HudPosition{" + anchorEdge + "/" + horizontalEdge + ", x=" + offsetX + ", y=" + offsetY + "}";
    }
}
