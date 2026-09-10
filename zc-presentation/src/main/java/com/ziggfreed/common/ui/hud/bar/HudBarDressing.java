package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.hud.card.HudCardLook;

/**
 * The three overlays that make a bar read as a bar, as the card's opacity leaves them: the dark
 * well behind the fill, the gloss across its top and the shade along its bottom. Both bar
 * documents draw them at fixed colours ({@code @TrackWell}, {@code @Gloss}, {@code @BaseShade}),
 * and this record MIRRORS those constants, the way {@link HudBarLayout} mirrors the geometry: the
 * two must move together, or a dimmed bar's dressing drifts from its shipped one.
 *
 * <p>The dressing FOLLOWS THE CARD with no leaf of its own to author. A card at full opacity
 * changes nothing ({@link #at} answers null, and the paint pushes nothing, so the documents' own
 * constants stand); a card at a fraction gets each overlay at its shipped colour with its shipped
 * alpha multiplied by that fraction ({@link HudCardLook#dimmed}), so a card at 72 percent gets a
 * well, a gloss and a shade at 72 percent of their strength and the bar keeps the same relationship
 * to its card at any opacity. The card's hue never bleeds in: the well stays the shipped near-black,
 * the gloss white, the shade black, which is what keeps a tinted card readable.
 *
 * @param well  the eight-digit hex to push on {@code #Track}
 * @param gloss the eight-digit hex to push on {@code #Track #Fill #Gloss}
 * @param shade the eight-digit hex to push on {@code #Track #Fill #Base}
 */
public record HudBarDressing(@Nonnull String well, @Nonnull String gloss, @Nonnull String shade) {

    /** The well's colour, the documents' {@code @TrackWell}. */
    public static final String WELL_RGB = "#05080c";

    /** The well's alpha, the documents' {@code @TrackWell}. */
    public static final double WELL_ALPHA = 0.72;

    /** The gloss's colour, the documents' {@code @Gloss}. */
    public static final String GLOSS_RGB = "#ffffff";

    /** The gloss's alpha, the documents' {@code @Gloss}. */
    public static final double GLOSS_ALPHA = 0.22;

    /** The shade's colour, the documents' {@code @BaseShade}. */
    public static final String SHADE_RGB = "#000000";

    /** The shade's alpha, the documents' {@code @BaseShade}. */
    public static final double SHADE_ALPHA = 0.22;

    /**
     * The dressing a card at {@code opacity} (0 to 1) leaves: null at full opacity, because the
     * documents already draw it and nothing need be pushed; else the three overlays dimmed to
     * follow the card. Pure.
     */
    @Nullable
    public static HudBarDressing at(double opacity) {
        if (opacity >= 1.0) {
            return null;
        }
        return new HudBarDressing(
                HudCardLook.dimmed(WELL_RGB, WELL_ALPHA, opacity),
                HudCardLook.dimmed(GLOSS_RGB, GLOSS_ALPHA, opacity),
                HudCardLook.dimmed(SHADE_RGB, SHADE_ALPHA, opacity));
    }
}
