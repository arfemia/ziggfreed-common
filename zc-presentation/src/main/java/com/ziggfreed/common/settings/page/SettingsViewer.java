package com.ziggfreed.common.settings.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;

/**
 * Who is looking at the Settings tab: the handles a row reads and writes its player's state through. Any
 * of them may be null (a test, a build with no player), and a row then reads its defaults and keeps
 * nothing. World thread.
 */
public record SettingsViewer(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
                             @Nullable Player player, @Nullable PlayerRef playerRef) {

    /** Nobody: every row reads its defaults and no write is kept. */
    public static final SettingsViewer NOBODY = new SettingsViewer(null, null, null, null);

    /** The viewer behind a page's handles. */
    @Nonnull
    public static SettingsViewer of(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable Player player) {
        return new SettingsViewer(store, ref, player, player == null ? null : PlayerAccess.playerRef(player));
    }
}
