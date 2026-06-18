package com.ziggfreed.common.instance.result;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * The locale-free chrome text for the {@link ResultsPage} (the {@code QueueMessages}
 * convention). The consumer returns pre-built, client-resolved {@link Message}s from its
 * own {@code Lang}; common stays locale-free.
 */
public interface ResultsMessages {

    /** The tinted outcome banner title for this {@link ResultKind}. */
    @Nonnull Message outcomeTitle(@Nonnull ResultKind kind);

    /** The run duration line ("Time: m:ss"). */
    @Nonnull Message duration(int seconds);

    @Nonnull Message breakdownTitle();

    @Nonnull Message rewardsTitle();

    /** Shown when the run granted no rewards. */
    @Nonnull Message noRewards();

    /** A note shown when a reward is pending (blocked by a full inventory). */
    @Nonnull Message pendingNote();

    @Nonnull Message viewLeaderboardButton();

    @Nonnull Message playAgainButton();

    @Nonnull Message closeButton();

    /** A "Team total: {total}" label (multi-team modes). */
    @Nonnull Message teamTotal(long total);
}
