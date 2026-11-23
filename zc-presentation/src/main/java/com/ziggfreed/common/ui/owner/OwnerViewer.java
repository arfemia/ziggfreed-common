package com.ziggfreed.common.ui.owner;

import java.util.Objects;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The owner looking at the owner menu, as a tile is asked about them: whether it shows ({@link OwnerTile#shown}) and
 * what pressing it does. World thread.
 */
public record OwnerViewer(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Player player,
        @Nonnull PlayerRef playerRef) {

    public OwnerViewer {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(playerRef, "playerRef");
    }
}
