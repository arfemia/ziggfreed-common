package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Paints a header {@link Stat} into an {@code @ZigStat} instance ({@code #Value} figure, {@code #Label} caption).
 * The figure's colour is its tone's (gold for {@link Tone#COLLECT}, strong ink for {@link Tone#NEUTRAL}), pushed on
 * the label's colour alone so the template's alignment and size stay as authored; painted both ways, so a header
 * repaint in a partial update is clean.
 */
public final class StatPainter {

    private StatPainter() {
    }

    /**
     * @param selector the {@code @ZigStat} instance's id
     */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull String selector, @Nonnull Stat stat) {
        KitPaint.text(cmd, selector + " #Value", stat.value());
        KitPaint.text(cmd, selector + " #Label", stat.label());
        cmd.set(selector + " #Value.Style.TextColor", figureColour(stat.tone()));
    }

    @Nonnull
    static String figureColour(@Nonnull Tone tone) {
        if (tone == Tone.NEUTRAL) {
            return ZigTokens.INK_STRONG;
        }
        if (tone == Tone.COLLECT) {
            return ZigTokens.ACCENT;
        }
        return tone.textHex();
    }
}
