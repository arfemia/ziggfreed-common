package com.ziggfreed.common.lobby;

import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The consumer-supplied provider of pre-built, already-localized {@link Message}s for
 * each queue moment, so {@code ziggfreed-common} stays locale-free (it never reads a
 * locale; it only delivers Messages the consumer hands it, via {@code Notify} toasts /
 * {@code EventTitles} banners). Every method defaults to {@code null} (suppress that
 * feedback), so a consumer overrides only what it wants to show.
 *
 * <p>The lobby calls these on the queue's timer thread and delivers with packet-only
 * writes (no Store/Ref reads), so an implementation must just RETURN a Message and do
 * no world work.
 */
public interface QueueMessages {

    /** Toast when a player joins (e.g. "Joined the hunt queue (2/4)"). */
    @Nullable
    default Message joined(int size, int minParty, int maxParty) {
        return null;
    }

    /** Toast (to the leaver) when a player leaves the queue. */
    @Nullable
    default Message left(int size, int minParty, int maxParty) {
        return null;
    }

    /** Centered-banner primary line for the per-second countdown (e.g. "Entering in {n}..."). */
    @Nullable
    default Message countdownPrimary(int secondsRemaining) {
        return null;
    }

    /** Centered-banner secondary line for the per-second countdown (shown only if both lines are non-null). */
    @Nullable
    default Message countdownSecondary(int secondsRemaining) {
        return null;
    }

    /** Toast to the launching party the moment the launcher fires. */
    @Nullable
    default Message launching() {
        return null;
    }

    /** Toast to the remaining players when a started countdown is cancelled (dropped below the floor). */
    @Nullable
    default Message cancelled() {
        return null;
    }

    /** Toast to the party when the launcher itself failed (the round could not start). */
    @Nullable
    default Message launchFailed() {
        return null;
    }
}
