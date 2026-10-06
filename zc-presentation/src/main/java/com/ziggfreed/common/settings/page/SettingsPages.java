package com.ziggfreed.common.settings.page;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/** The way in to the Settings tab, for the destination and {@code /zighud}. World thread. */
public final class SettingsPages {

    private SettingsPages() {
    }

    /** Open the Settings tab for {@code player}. True when the screen was taken. */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[settings] the Settings tab was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new SettingsPage(playerRef));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[settings] the Settings tab failed to open", t);
            return false;
        }
    }
}
