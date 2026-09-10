package com.ziggfreed.common.ui.hud.panel;

/**
 * Where a value stands when its row is moved: {@code current} out of {@code maximum}, handed over
 * by whoever reports the movement ({@link HudPanels#moved}) and kept on the row until its next
 * move. The panel never asks anything for a value: a row exists only because something moved, and
 * the mod that moved it already knows where the value stands, so the fill is drawn from what the
 * row holds. A maximum of zero or less reads as a full bar, so a value with no ceiling left (a
 * final stage) draws as complete rather than dividing by nothing.
 */
public record HudBarReading(double current, double maximum) {

    /** A bar drawn full. */
    public static final HudBarReading FULL = new HudBarReading(1, 1);

    /** The fill fraction, held to {@code 0..1}. */
    public double fraction() {
        if (maximum <= 0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, current / maximum));
    }
}
