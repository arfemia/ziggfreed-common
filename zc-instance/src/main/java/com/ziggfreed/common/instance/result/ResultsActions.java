package com.ziggfreed.common.instance.result;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The consumer's handlers for the results-screen footer CTAs (the explicit "CTA to
 * leaderboard" ask + Play Again). Kept a seam so common never imports a mod's leaderboard
 * page or queue wiring. Both default to no-ops (the page hides the button when a handler
 * is not provided).
 */
public interface ResultsActions {

    /** Open the leaderboard, deep-linked to {@code bucket} (the just-played bucket). */
    default void viewLeaderboard(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store, @Nullable String bucket) {
    }

    /** Re-queue / re-open the queue screen ("Play Again"). */
    default void playAgain(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Store<EntityStore> store) {
    }

    /**
     * Whether the page offers Play Again at all. True by default, so a consumer with a queue keeps its button; a
     * walk-in run with nothing to queue again (a dungeon, which a player re-enters at its gate) answers false and
     * the page shows no button that would do nothing.
     */
    default boolean offersPlayAgain() {
        return true;
    }

    /**
     * Claim (grant) the run's pending spoils, applying the full-inventory guard. Returns
     * {@code true} when everything was delivered, {@code false} when some reward could not fit
     * and is held for a later claim (the page then shows the "make space" note). The default
     * grants nothing and reports all-claimed (a consumer with no claimable rewards).
     */
    default boolean claimRewards(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store) {
        return true;
    }

    /**
     * What a Claim press did: whether everything was delivered ({@link #claimRewards}'s answer), and the toast it
     * raises over the page, or null for the page's own claimed or pending line.
     */
    record Claimed(boolean allClaimed, @Nullable ToastSpec toast) {
    }

    /**
     * Claim the run's pending spoils as {@link #claimRewards} does, answering the toast the press raises too. The
     * default claims through {@link #claimRewards} and raises none of its own, so the page shows its own line; a
     * consumer whose press hands over the library's Claims answers the Claims tab's own toast, naming what landed.
     */
    @Nonnull
    default Claimed claim(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return new Claimed(claimRewards(ref, store), null);
    }
}
