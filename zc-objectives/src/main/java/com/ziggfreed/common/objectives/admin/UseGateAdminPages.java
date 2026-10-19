package com.ziggfreed.common.objectives.admin;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to {@link UseGateAdminPage}: a direct static call from a consumer's own gated admin surface, never a
 * registered destination. It shares the progression admin page's deps ({@link ProgressionAdminPages#deps}): the
 * same audience (DENY until a consumer fills it), the same theme and the same Back, so a consumer that wired one
 * admin page has wired both. World thread.
 */
public final class UseGateAdminPages {

    private UseGateAdminPages() {
    }

    /**
     * Open the Use Gates page for {@code player}, audience-checked. True when the screen was taken; false when the
     * audience refused (the default), the entity is not a player, or the open threw.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        ProgressionAdminDeps deps = ProgressionAdminPages.resolvedDeps();
        if (!deps.mayOpenGuarded(store, ref, player)) {
            SafeLog.fine("[usegate-admin] the Use Gates page was refused by the audience seam");
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[usegate-admin] the Use Gates page was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new UseGateAdminPage(playerRef, 0));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[usegate-admin] the Use Gates page failed to open", t);
            return false;
        }
    }
}
