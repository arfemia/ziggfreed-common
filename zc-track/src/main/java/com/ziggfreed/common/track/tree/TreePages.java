package com.ziggfreed.common.track.tree;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.pick.TrackTree;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one way a track's tree page opens: the {@code Track_Tree} destination ({@link TreeDestinations}: the Claims
 * tab's choices waiting, a link, a conversation) and a consumer's own button (a skill's page) both land here. A
 * consumer asks {@link #has} before it offers the way in. World thread.
 */
public final class TreePages {

    private TreePages() {
    }

    /** Does {@code trackId} (any case) carry a tree the page can show (a pick bought outright with a Branch)? */
    public static boolean has(@Nullable String trackId) {
        return TrackTree.has(trackId);
    }

    /**
     * Open {@code trackId}'s tree for {@code player}, on their own entity {@code ref}. A track with no tree here
     * opens nothing and tells the player so.
     *
     * @return whether the screen was taken
     */
    public static boolean open(@Nullable String trackId, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[tree] a tree page was asked for by an entity that is not a player");
            return false;
        }
        if (!has(trackId)) {
            playerRef.sendMessage(TreeText.line("page.empty"));
            return false;
        }
        try {
            String id = TrackService.def(trackId).id();
            player.getPageManager().openCustomPage(ref, store, new TreePage(playerRef, id));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[tree] the tree page failed to open", t);
            return false;
        }
    }
}
