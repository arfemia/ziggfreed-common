package com.ziggfreed.common.loot.reward;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * Pays out a list of rewards without ever throwing, and without letting one bad reward cost the
 * player the rest.
 *
 * <p>The rule is per-reward isolation with a fallback. Each reward is attempted on its own; if its
 * handler throws, the engine asks that handler for a replayable console command and hands it to the
 * consumer's retry queue for next connect. Only a reward that can be neither granted NOR queued
 * counts as lost, and the caller sees exactly which of the three happened in the returned
 * {@link GrantOutcome}.
 *
 * <p>That distinction matters more than it looks. Without it, a payout loop aborts at the first
 * failure and every reward after it silently disappears - the player finishes whatever earned the
 * payout and gets part of it, with nothing anywhere saying so.
 */
public final class RewardGrants {

    /**
     * What became of a payout pass. {@code granted} reached the player now, {@code queued} will on
     * their next connect, {@code failed} did not and will not.
     */
    public record GrantOutcome(int granted, int queued, int failed) {

        /** Nothing to pay out. */
        public static final GrantOutcome EMPTY = new GrantOutcome(0, 0, 0);

        /** True when at least one reward reached the player or is waiting to. */
        public boolean anyDelivered() {
            return granted > 0 || queued > 0;
        }

        /** True when every reward was delivered or queued - nothing was lost. */
        public boolean complete() {
            return failed == 0;
        }
    }

    private RewardGrants() {
    }

    /**
     * Grant every reward in {@code rewards} to {@code subject}.
     *
     * @param sourceId   labels the payout in logs and in any retry command, conventionally
     *                   {@code "<what paid out>:<its id>"}
     * @param kinds      the vocabulary each spec's kind is looked up in
     * @param retryQueue persists a replayable console command for a failed reward; null means there
     *                   is nowhere to queue, so a failure is simply reported
     * @param warn       where failure reports go
     */
    @Nonnull
    public static GrantOutcome grantAll(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject,
                                        @Nonnull String sourceId, @Nonnull RewardKindRegistry kinds,
                                        @Nullable BiConsumer<Subject, String> retryQueue,
                                        @Nonnull Consumer<String> warn) {
        if (rewards.isEmpty()) {
            return GrantOutcome.EMPTY;
        }
        int granted = 0;
        int queued = 0;
        int failed = 0;
        for (RewardSpec spec : rewards) {
            RewardHandler handler = kinds.handler(spec.kind());
            if (handler == null) {
                failed++;
                warn.accept("[grant] " + sourceId + ": no handler registered for reward kind '"
                        + spec.kind() + "' - nothing was paid out for it");
                continue;
            }
            try {
                handler.grant(spec, subject, sourceId);
                granted++;
            } catch (Throwable t) {
                kinds.recordFailure(spec.kind(), t.getMessage());
                if (queueRetry(spec, subject, sourceId, handler, retryQueue, warn)) {
                    queued++;
                    continue;
                }
                failed++;
                warn.accept("[grant] " + sourceId + ": reward lost (" + spec.kind() + "): " + t.getMessage());
            }
        }
        return new GrantOutcome(granted, queued, failed);
    }

    /**
     * Try to turn a failed reward into a queued retry. False when the handler offers no replayable
     * form, there is nowhere to queue it, or the queue itself failed - all three mean the reward is
     * lost, and all three are reported.
     */
    private static boolean queueRetry(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                                      @Nonnull String sourceId, @Nonnull RewardHandler handler,
                                      @Nullable BiConsumer<Subject, String> retryQueue,
                                      @Nonnull Consumer<String> warn) {
        if (retryQueue == null) {
            return false;
        }
        String command;
        try {
            command = handler.retryCommand(spec, subject, sourceId);
        } catch (Throwable t) {
            warn.accept("[grant] " + sourceId + ": could not build a retry for '" + spec.kind()
                    + "': " + t.getMessage());
            return false;
        }
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            retryQueue.accept(subject, command);
            warn.accept("[grant] " + sourceId + ": reward '" + spec.kind()
                    + "' failed and was queued for next connect: " + command);
            return true;
        } catch (Throwable t) {
            warn.accept("[grant] " + sourceId + ": retry queue also failed: " + t.getMessage());
            return false;
        }
    }
}
