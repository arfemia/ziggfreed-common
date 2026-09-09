package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The wide panel in the top-right: values that move while a player is simply out in the world,
 * where there is no ledger for them to sit beside. It spreads sideways as soon as a second row
 * appears, so one moving value is a single bar rather than a lone entry in an empty column, and
 * nine of them are a compact block instead of a tower.
 *
 * <p>It is pinned to the RIGHT edge, so it grows LEFTWARD and its first column is the rightmost
 * one. That is what keeps it clear of the quest tracker below: with a column opening per row, six
 * values moving at once draw one wide row rather than a tower, and the panel only gains height once
 * there are more values than columns to put them in.
 *
 * <p>Layout and nothing else - every line of the drawing is {@link HudBarHud}'s. The numbers below
 * mirror {@code Hud/ZigHudBarGrid.ui} and must move with it: six columns of three slots, 12 of
 * horizontal padding, a 200-wide column and 8 between columns (so the panel runs 224 wide with one
 * column open to 1264 with all six), and a track spanning 140 of that column between the two
 * 26-wide end captions, padded a pixel each side (so a fill spans 138).
 */
public final class HudBarGridHud extends HudBarHud {

    /** This panel's key on the native per-player {@code HudManager}, under this library's own id. */
    public static final String HUD_KEY = "ziggfreedcommon:hud_bar_grid";

    /** Where the document, the element names and the declared slot counts are stated once. */
    public static final HudBarLayout LAYOUT = new HudBarLayout(
            HudBarPanelAsset.GRID_ID,
            HUD_KEY,
            "Hud/ZigHudBarGrid.ui",
            "#ZigHudBarGridPanel",
            6,
            3,
            12,
            200,
            8,
            130,
            HudBarPanelAsset.GRID_POSITION);

    public HudBarGridHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, LAYOUT);
    }
}
