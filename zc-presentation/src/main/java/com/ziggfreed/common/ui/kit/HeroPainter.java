package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.ui.ZigFrame;

/**
 * Paints the band frame's hero ({@code @ZigDecoratedFrame}'s {@code #Hero} in {@code Common/ZigFrames.ui}, its ids
 * named by {@link ZigFrame}): shown, the emblem row shown with its picture or hidden without one, the headline under
 * the game's gold text gradient, the copper tagline and the line under the rule each shown with words or hidden. Every
 * part is painted both ways, so a partial update that repaints the hero clears what the last one showed. A page on the
 * band frame calls this once in its build; a page that shows no hero never calls it, and the hero stays hidden.
 */
public final class HeroPainter {

    private HeroPainter() {
    }

    /** Fill and show the frame's hero with {@code hero}. */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull Hero hero) {
        cmd.set(ZigFrame.HERO + ".Visible", true);
        String emblem = hero.emblem();
        cmd.set(ZigFrame.HERO_EMBLEM + ".Visible", emblem != null);
        if (emblem != null) {
            cmd.set(ZigFrame.HERO_EMBLEM_ART + ".AssetPath", emblem);
        }
        KitPaint.text(cmd, ZigFrame.HERO_HEADLINE, hero.headline());
        KitPaint.optional(cmd, ZigFrame.HERO_TAGLINE, hero.tagline());
        KitPaint.optional(cmd, ZigFrame.HERO_SUB, hero.sub());
    }
}
