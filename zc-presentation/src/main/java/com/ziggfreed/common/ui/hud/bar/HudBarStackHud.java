package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * The tall panel in the left column: the running ledger of whatever a player is in the middle of
 * doing. It stays one narrow column while only a few rows are moving, exactly as a stack of notices
 * would, and opens a second column once a genuinely busy stretch pushes past its spot's
 * {@code RowsPerColumn} rather than running off the bottom of the screen.
 *
 * <p>Layout and nothing else - every line of the drawing is {@link HudBarHud}'s. The numbers below
 * mirror {@code Hud/ZigHudBars.ui} and must move with it: two columns of thirteen slots, 12 of
 * horizontal padding, a 296-wide column and 12 between columns (so the panel is 320 wide with one
 * column open and 628 with both), and a track spanning 228 of that column between the two 30-wide
 * end captions, padded a pixel each side (so a fill spans 226).
 *
 * <p>Where it sits is its {@code Default.json} panel file's {@code Placement}, the shipped
 * {@code TopLeft} spot: the same origin as the zone card a companion mod draws at the top-left,
 * deliberately OVER it, since this panel attaches after that card at ready and is transparent
 * behind its frame. The document holds the panel to that card's own height as a floor, so a ledger
 * of one or two rows covers the card rather than leaving its lower half showing. The corner below is
 * only the document's fallback for a server where that file is gone.
 */
public final class HudBarStackHud extends HudBarHud {

    /** This panel's key on the native per-player {@code HudManager}, under this library's own id. */
    public static final String HUD_KEY = "ziggfreedcommon:hud_bars";

    /** Where the document's fallback anchor sits: the top-left, at the zone card's own origin. */
    public static final HudPosition FALLBACK_POSITION =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 90);

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
            FALLBACK_POSITION);

    public HudBarStackHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, LAYOUT);
    }
}
