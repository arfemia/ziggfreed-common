package com.ziggfreed.common.objectives.hud;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.settings.PlayerSettings;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.panel.HudSpotAsset;
import com.ziggfreed.common.ui.hud.panel.HudSpotConfig;
import com.ziggfreed.common.ui.hud.panel.HudSpotPosition;

/**
 * Where one player's tracker sits: the spot they picked, when it is still on and offered to the tracker,
 * folded leaf by leaf over the server's own position (the consumer's layout, else the native corner),
 * when the result still pins the top of the screen; otherwise the server's position. A pick is a spot
 * id, so an owner who later removes, switches off or re-targets that spot puts the player back on the
 * server's position rather than nowhere.
 */
final class TrackedQuestSpot {

    private TrackedQuestSpot() {
    }

    @Nonnull
    static HudPosition position(@Nonnull HudPosition server, @Nullable String pick, @Nonnull HudSpotConfig spots) {
        HudSpotAsset spot = spots.spot(pick);
        if (spot == null || !spot.enabled() || !spot.fits(PlayerSettings.QUEST_TRACKER)) {
            return server;
        }
        HudSpotPosition authored = spot.position();
        HudPosition folded = authored == null ? server : authored.over(server);
        // The tracker hugs its quests from the top down, and a content-sized anchor is proven only for a
        // TOP pin, so a spot that would pin it anywhere else is not taken.
        return folded.getAnchorEdge() == HudPosition.AnchorEdge.TOP ? folded : server;
    }
}
