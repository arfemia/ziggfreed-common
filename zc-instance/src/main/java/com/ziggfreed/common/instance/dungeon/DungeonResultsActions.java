package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.claims.ClaimGrants;
import com.ziggfreed.common.instance.claims.ZcRewardSink;
import com.ziggfreed.common.instance.result.ResultsActions;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A dungeon results screen's buttons: Claim hands the viewer's completion Claim over through the shared claim
 * helper ({@link ClaimGrants}, the bag guard putting back what does not fit, so the rest waits on the Claims tab),
 * and there is no Play Again, since a dungeon is entered again at its gate. Immutable; one per screen, holding the
 * id of the Claim that screen shows (null when the clear wrote none, and then the page shows no Claim button).
 */
final class DungeonResultsActions implements ResultsActions {

    @Nullable private final String claimId;

    DungeonResultsActions(@Nullable String claimId) {
        this.claimId = claimId;
    }

    @Override
    public boolean offersPlayAgain() {
        return false;
    }

    /**
     * Take the viewer's Claim and hand it over; true when nothing was kept back for a full bag (a Claim already
     * taken on the Claims tab counts as taken). World thread, inside the page's handler; never throws.
     */
    @Override
    public boolean claimRewards(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (claimId == null) {
            return true;
        }
        try {
            PlayerRef player = PlayerAccess.playerRef(store, ref);
            if (player == null || player.getUuid() == null) {
                return false;
            }
            return !ClaimGrants.claim(ClaimStore.shared(), player.getUuid(), claimId, ref, store,
                    ZcRewardSink.INSTANCE).anyKept();
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the completion Claim '" + claimId + "' could not be handed over from the results"
                    + " screen; it waits on the Claims tab", t);
            return false;
        }
    }
}
