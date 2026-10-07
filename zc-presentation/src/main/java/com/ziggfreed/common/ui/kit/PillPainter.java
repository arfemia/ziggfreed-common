package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.ui.UiRetint;

/**
 * Paints a {@link Pill} into a pill element ({@code @ZigPill} or {@code Pages/ZigPill.ui}'s {@code #Pill}): a tone
 * pill is the word in its tone's colour on the scrim with the tone's dot; a pill with its own fill (a season's
 * accent) is that fill, clamped like any data accent ({@link ZigTokens#clampAccent}), with the word in whichever ink
 * reads on it ({@link ZigTokens#inkOn}) and no dot. A fill that is not a {@code #rrggbb} paints as the tone pill.
 * Every leaf is painted both ways, so a live pill (the hero's chip) repaints cleanly.
 */
public final class PillPainter {

    private PillPainter() {
    }

    /**
     * @param selector the pill element itself (an inline instance's id, or an appended template's {@code #Pill})
     */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull String selector, @Nonnull Pill pill) {
        KitPaint.text(cmd, selector + " #Label", pill.label());
        String own = pill.fillHex();
        if (own != null && UiRetint.isSixDigitHex(own)) {
            String fill = ZigTokens.clampAccent(own);
            UiRetint.fill(cmd, selector, fill);
            cmd.set(selector + " #Label.Style.TextColor", ZigTokens.inkOn(fill));
            cmd.set(selector + " #Dot.Visible", false);
            return;
        }
        UiRetint.fill(cmd, selector, ZigTokens.SURFACE_SCRIM);
        cmd.set(selector + " #Label.Style.TextColor", pill.tone().textHex());
        String dot = pill.tone().fillHex();
        cmd.set(selector + " #Dot.Visible", dot != null);
        if (dot != null) {
            UiRetint.retintColor(cmd, selector + " #Dot", dot);
        }
    }
}
