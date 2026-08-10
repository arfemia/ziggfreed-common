package com.ziggfreed.common.instance.result;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The consumer's handlers for the results-screen footer CTAs (the explicit "CTA to
 * leaderboard" ask + Play Again). Kept a seam so common never imports a mod's leaderboard
 * page or queue wiring. Both default to no-ops (the page hides the button when a handler
 * is not provided).
 */
public interface ResultsActions {

    /** Open the leaderboard, deep-linked to {@code bucket} (the just-played bucket). */
    default void viewLeaderboard(@Nonnull PlayerRef player, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store, @Nullable String bucket) {
    }

    /** Re-queue / re-open the queue screen ("Play Again"). */
    default void playAgain(@Nonnull PlayerRef player, @Nonnull Ref<EntityStore> ref,
                           @Nonnull Store<EntityStore> store) {
    }

    /**
     * Claim (grant) the run's pending spoils, applying the full-inventory guard. Returns
     * {@code true} when everything was delivered, {@code false} when some reward could not fit
     * and is held for a later claim (the page then shows the "make space" note). The default
     * grants nothing and reports all-claimed (a consumer with no claimable rewards).
     */
    default boolean claimRewards(@Nonnull PlayerRef player, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store) {
        return true;
    }
}
