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
 * The way in to {@link KillDropsAdminPage}: the owner menu's Kill Drops tile, or a consumer's own gated admin surface,
 * never a registered destination. It shares the progression admin page's deps ({@link ProgressionAdminPages#deps}),
 * as the Use Gates page does: the same audience (the library's owner audience until a consumer fills it), the same
 * theme and the same Back, so a consumer that wired one admin page has wired all three. World thread.
 */
public final class KillDropsAdminPages {

    private KillDropsAdminPages() {
    }

    /**
     * Open the Kill Drops page for {@code player}, audience-checked. True when the screen was taken; false when the
     * audience refused, the entity is not a player, or the open threw.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        ProgressionAdminDeps deps = ProgressionAdminPages.resolvedDeps();
        if (!deps.mayOpenGuarded(store, ref, player)) {
            SafeLog.fine("[loot] the kill drops screen was refused by the audience seam");
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[loot] the kill drops screen was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new KillDropsAdminPage(playerRef, 0));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[loot] the kill drops screen failed to open", t);
            return false;
        }
    }
}
