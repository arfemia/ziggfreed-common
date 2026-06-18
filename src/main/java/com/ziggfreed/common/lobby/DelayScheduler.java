package com.ziggfreed.common.lobby;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

/**
 * The minimal one-shot delayed-task seam the lobby's fill window + countdown run on.
 * Abstracting it (rather than using a {@code ScheduledExecutorService} directly) keeps
 * the {@link MatchmakingQueue} state machine deterministically unit-testable: a test
 * injects a manual scheduler and fires pending tasks itself, instead of waiting on
 * real wall-clock time.
 *
 * <p>The production implementation ({@link LobbyService} owns it) wraps a single daemon
 * {@code ScheduledExecutorService}, mirroring Kweebec's {@code RoundStateMachine}.
 */
public interface DelayScheduler {

    /** Schedule {@code task} to run once after {@code delay}; the returned handle can cancel it. */
    @Nonnull
    Cancellable schedule(@Nonnull Runnable task, long delay, @Nonnull TimeUnit unit);

    /** A handle to a scheduled task. */
    interface Cancellable {
        /** Cancel the task if it has not already run. Idempotent. */
        void cancel();
    }
}
