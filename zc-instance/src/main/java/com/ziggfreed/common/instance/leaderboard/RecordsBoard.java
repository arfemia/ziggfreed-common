package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The board the Records tab opens while it shows: the seam {@link RecordsDestinations#setBoard} fills (the
 * stat board). With none filled, Records shows no tab.
 */
public interface RecordsBoard {

    /** Whether the board has anything to show now. */
    boolean shows();

    /** The page to open now, or null when there is nothing to open. */
    @Nullable
    LeaderboardPageDeps deps();

    /**
     * Records is opening for the player at {@code ref}, before {@link #shows} and {@link #deps} are asked:
     * the board's chance to bring that player's own numbers up to date. Nothing, the default. World thread.
     */
    default void opening(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
    }
}
