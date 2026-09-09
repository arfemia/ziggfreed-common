package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * The wide panel: values that move while a player is simply out in the world, where there is no
 * ledger for them to sit beside. It spreads sideways as soon as a second row appears, so one moving
 * value is a single bar rather than a lone entry in an empty column.
 *
 * <p>Where it sits is its {@code Grid.json} panel file's {@code Placement}: the shipped
 * {@code BottomLeft} spot, two columns wide, just below the player's own bars and clear of the
 * hotbar, pinned to the bottom edge so it grows UPWARD and fills each column from the bottom. The
 * shipped {@code TopRight} spot is the other one measured for it, six columns wide and pinned to the
 * right edge so it grows LEFTWARD, its first column the rightmost. The corner below is only the
 * document's fallback for a server where the panel file is gone.
 *
 * <p>Layout and nothing else - every line of the drawing is {@link HudBarHud}'s. The numbers below
 * mirror {@code Hud/ZigHudBarGrid.ui} and must move with it: six columns of three slots, 12 of
 * horizontal padding, a 200-wide column and 8 between columns (so the panel runs 224 wide with one
 * column open, 432 with two, to 1264 with all six), and a track spanning 132 of that column between
 * the two 30-wide end captions, padded a pixel each side (so a fill spans 130).
 */
public final class HudBarGridHud extends HudBarHud {

    /** This panel's key on the native per-player {@code HudManager}, under this library's own id. */
    public static final String HUD_KEY = "ziggfreedcommon:hud_bar_grid";

    /** Where the document's fallback anchor sits: the bottom-left, just above the screen's edge. */
    public static final HudPosition FALLBACK_POSITION =
            new HudPosition(HudPosition.AnchorEdge.BOTTOM, HudPosition.HorizontalEdge.LEFT, 16, 16);

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
            FALLBACK_POSITION);

    public HudBarGridHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, LAYOUT);
    }
}
