package com.ziggfreed.common.instance.queue;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * The locale-free chrome text for the {@link QueuePage} (the {@code QueueMessages} /
 * {@code PartyScreenMessages} convention): titles, the status lines, the wait estimate,
 * and the Leave button. The consumer returns pre-built, client-resolved {@link Message}s
 * from its own {@code Lang}, so common stays locale-free.
 */
public interface QueueScreenMessages {

    @Nonnull Message title();

    /** A "{size}/{max} players" label. */
    @Nonnull Message playerCount(int size, int maxSize);

    /** Status while gathering players. */
    @Nonnull Message statusWaiting();

    /** Status during the launch countdown ("Launching in {seconds}"). */
    @Nonnull Message statusCountdown(int seconds);

    /** Status at launch. */
    @Nonnull Message statusLaunching();

    /** A derived upper-bound wait estimate ("up to ~{seconds}s"). */
    @Nonnull Message waitEstimate(int seconds);

    @Nonnull Message leaveButton();

    @Nonnull Message refreshButton();

    /** Shown when the viewer is no longer in any queue (left, or already launched). */
    @Nonnull Message notInQueue();

    /**
     * One-shot in-page toast shown when the queue screen first opens for a queued player
     * ("You're in the queue - {size}/{max}"). The notification feed is hidden behind the open
     * menu, so this is the visible confirmation that the queue is live.
     */
    @Nonnull Message toastQueued(int size, int maxSize);
}
