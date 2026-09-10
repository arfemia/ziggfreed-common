package com.ziggfreed.common.ui.hud.panel;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * The wide panel: values that move while a player is simply out in the world, where there is no
 * ledger for them to sit beside. It spreads sideways as soon as a second row appears, so one moving
 * value is a single bar rather than a lone entry in an empty column.
 *
 * <p>Where it sits is its {@code World_Bars.json} panel file's {@code Placement}: the shipped
 * {@code Top_Right} spot, six columns wide above the quest tracker and pinned to the right edge,
 * so it grows LEFTWARD as columns open and its first column is the rightmost. The shipped
 * {@code Bottom_Left} spot is the other one measured for it: three columns wide, pinned to the
 * bottom edge so it grows UPWARD and fills each column from the bottom, with a band left clear
 * after its third row so the stack straddles the player's own health and mana bars rather than
 * climbing into them, and the lowest cells of its third column left empty so the panel steps
 * around the utility slot and the hotbar's end under them. The corner below is only the
 * document's fallback for a server where the panel file is gone.
 *
 * <p>Layout and nothing else - every line of the drawing is {@link HudPanelHud}'s. The numbers below
 * mirror {@code Hud/ZigHudBarGrid.ui} and must move with it: six columns of nine slots, 12 of
 * horizontal padding, a 200-wide column and 8 between columns (so the panel runs 224 wide with one
 * column open, 432 with two, 640 with three, to 1264 with all six), a track spanning 132 of that
 * column between the two 30-wide end captions, padded a pixel each side (so a fill spans 130), and
 * for the height 12 of vertical padding, a 5 margin above every row, an 18 line and a 12 bar block
 * (so a row with a fill is 35 tall and a row about an item 23).
 */
public final class WorldPanelHud extends HudPanelHud {

    /** This panel's key on the native per-player {@code HudManager}, under this library's own id. */
    public static final String HUD_KEY = "ziggfreedcommon:world_bars";

    /** Where the document's fallback anchor sits: the bottom-left, just above the screen's edge. */
    public static final HudPosition FALLBACK_POSITION =
            new HudPosition(HudPosition.AnchorEdge.BOTTOM, HudPosition.HorizontalEdge.LEFT, 16, 16);

    /** Where the document, the element names and the declared slot counts are stated once. */
    public static final HudPanelLayout LAYOUT = new HudPanelLayout(
            HudPanelAsset.WORLD_ID,
            HUD_KEY,
            "Hud/ZigHudBarGrid.ui",
            "#ZigHudBarGridPanel",
            6,
            9,
            12,
            200,
            8,
            130,
            12,
            5,
            18,
            12,
            FALLBACK_POSITION);

    public WorldPanelHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, LAYOUT);
    }
}
