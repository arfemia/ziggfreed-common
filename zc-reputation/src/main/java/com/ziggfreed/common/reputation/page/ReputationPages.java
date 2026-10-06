package com.ziggfreed.common.reputation.page;

import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.reputation.ReputationRuntime;
import com.ziggfreed.common.util.SafeLog;

/** Opens the Reputation page on the player's own ref; declines while the module is off or nobody is there. */
public final class ReputationPages {

    private ReputationPages() {
    }

    /** Open on {@code reputationId} (null for the first met one); false when nothing opened. World thread. */
    public static boolean open(@Nullable String reputationId, @Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref, @Nullable Player player) {
        if (store == null || ref == null || player == null || !ReputationRuntime.service().isOn()) {
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        if (playerRef == null) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new ReputationPage(playerRef, reputationId));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the Reputation page could not open", t);
            return false;
        }
    }
}
