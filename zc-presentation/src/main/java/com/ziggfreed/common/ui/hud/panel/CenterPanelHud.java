package com.ziggfreed.common.ui.hud.panel;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * The centred panel: a short stack in the middle of the screen for a value that moved while the
 * player could not see the HUD, because they had a page open (a board, a shop, a conversation): the
 * client draws no HUD over a page, so the reporting mod puts such a move here
 * ({@link HudPanels#movedInCenter}) rather than on a corner panel where it would run out unseen. A
 * row adds every further move under the same id to the one figure it shows ("+N", or "-N" for a net
 * loss) and goes away a few seconds after it can be seen.
 *
 * <p>The shipped {@code Center_Bars.json} sets {@code HoldWhilePageOpen}: a row moved under a page,
 * or still up when one opens, waits with its clock stopped and shows for its whole time once no page
 * is open ({@link HudPanelHud}). The rest of its file is a top-centre corner with no spot of its own
 * (none was measured for it, so a player may show or hide it but not move it), one column and a
 * {@code MaxVisible} of four, which is also the most rows that wait under a page.
 *
 * <p>Layout and nothing else: it draws the Activity ledger's own document, {@code Hud/ZigHudBars.ui}
 * (a 296-wide column, so a name, its gain and a 226-wide fill read comfortably at a glance), in a
 * layer of its own, and attaches after the other two so it draws over both where they meet. The
 * corner below is only the fallback for a server where the panel file is gone.
 */
public final class CenterPanelHud extends HudPanelHud {

    /** This panel's key on the native per-player {@code HudManager}, under this library's own id. */
    public static final String HUD_KEY = "ziggfreedcommon:center_bars";

    /**
     * Where the panel hangs when its file is gone: from the top edge, on the screen's centre line,
     * far enough down to clear the banners the game and a companion mod draw at the top.
     */
    public static final HudPosition FALLBACK_POSITION =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.CENTER, 0, 300);

    /** The ledger's document, slots and measures, under this panel's own id, key and corner. */
    public static final HudPanelLayout LAYOUT =
            LedgerPanelHud.LAYOUT.drawnAs(HudPanelAsset.CENTER_ID, HUD_KEY, FALLBACK_POSITION);

    public CenterPanelHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, LAYOUT);
    }
}
