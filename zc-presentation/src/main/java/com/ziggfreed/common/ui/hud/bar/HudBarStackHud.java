package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The tall panel in the left column: the running ledger of whatever a player is in the middle of
 * doing. It stays one narrow column while only a few rows are moving, exactly as a stack of notices
 * would, and opens a second column once a genuinely busy stretch pushes past its authored
 * {@code RowsPerColumn} rather than running off the bottom of the screen.
 *
 * <p>Layout and nothing else - every line of the drawing is {@link HudBarHud}'s. The numbers below
 * mirror {@code Hud/ZigHudBars.ui} and must move with it: two columns of thirteen slots, 12 of
 * horizontal padding, a 296-wide column and 12 between columns (so the panel is 320 wide with one
 * column open and 628 with both), and a track spanning 236 of that column between the two 26-wide
 * end captions, padded a pixel each side (so a fill spans 234).
 */
public final class HudBarStackHud extends HudBarHud {

    /** This panel's key on the native per-player {@code HudManager}, under this library's own id. */
    public static final String HUD_KEY = "ziggfreedcommon:hud_bars";

    /** Where the document, the element names and the declared slot counts are stated once. */
    public static final HudBarLayout LAYOUT = new HudBarLayout(
            HudBarPanelAsset.DEFAULT_ID,
            HUD_KEY,
            "Hud/ZigHudBars.ui",
            "#ZigHudBarsPanel",
            2,
            13,
            12,
            296,
            12,
            226,
            HudBarPanelAsset.DEFAULT_POSITION);

    public HudBarStackHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, LAYOUT);
    }
}
