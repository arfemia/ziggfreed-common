package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
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
     * The parameter naming what paid a reward out. Every kind reads the same one - it is what a
     * {@code {source}} placeholder in a command line resolves to, and what a log line names - so it
     * is written once here rather than spelled separately by each payout site.
     */
    public static final String P_SOURCE = "source";

    /**
     * The parameter saying whether a reward is worth waiting for. A reward authored
     * {@code queueifoffline: false} is only worth anything in the moment - a celebration effect, a
     * command that positions the player - so it is dropped rather than parked when nobody is there
     * to receive it. Absent reads as false.
     */
    public static final String P_QUEUE_IF_OFFLINE = "queueifoffline";

    /**
     * What became of a payout pass. {@code granted} reached the player now, {@code queued} will on
     * their next connect, {@code failed} did not and will not.
     *
     * <p>{@code receipt} is what the {@code granted} rewards ACTUALLY handed over, one spec per
     * thing, as each handler reported it through
     * {@link RewardHandler#grant(RewardSpec, Subject, String, java.util.function.Consumer)}: a kind
     * that pays exactly what it said reports the spec itself, a kind that rolls reports what landed.
     * A toast raised after the payout lists this rather than the authored list, so a rolled table
     * reads as the items it produced and an empty roll adds no row. A queued or failed reward is not
     * in it, because it was not handed over now.
     */
    public record GrantOutcome(int granted, int queued, int failed, @Nonnull List<RewardSpec> receipt) {

        /** Nothing to pay out. */
        public static final GrantOutcome EMPTY = new GrantOutcome(0, 0, 0);

        public GrantOutcome {
            receipt = List.copyOf(receipt);
        }

        /** The three counts with nothing reported handed over, for an outcome that carries no receipt. */
        public GrantOutcome(int granted, int queued, int failed) {
            this(granted, queued, failed, List.of());
        }

        /** True when at least one reward reached the player or is waiting to. */
        public boolean anyDelivered() {
            return granted > 0 || queued > 0;
        }

        /** True when every reward was delivered or queued - nothing was lost. */
        public boolean complete() {
            return failed == 0;
        }

        /**
         * What a toast raised after a payout lists: {@code paid}'s receipt, or nothing at all when
         * there was no payout ({@code paid} null). A hand-in or accept that parks a quest, a
         * refused claim, a settle that never ran - none of them handed anything over, and a toast
         * listing the authored rewards under a gold "complete" headline shows a player things they
         * were not given. The one rule every completion toast reads its rows through.
         */
        @Nonnull
        public static List<RewardSpec> receiptOf(@Nullable GrantOutcome paid) {
            return paid == null ? List.of() : paid.receipt();
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
        List<RewardSpec> receipt = new ArrayList<>(rewards.size());
        for (RewardSpec spec : rewards) {
            RewardHandler handler = kinds.handler(spec.kind());
            if (handler == null) {
                failed++;
                warn.accept("[grant] " + sourceId + ": no handler registered for reward kind '"
                        + spec.kind() + "' - nothing was paid out for it");
                continue;
            }
            // The handler writes onto a receipt of its own, kept only once the grant has returned:
            // a reward that throws half-way is queued or reported lost, and whatever it had
            // reported before the throw must not read as handed over.
            List<RewardSpec> handedOver = new ArrayList<>(1);
            try {
                handler.grant(spec, subject, sourceId, handedOver::add);
                granted++;
                receipt.addAll(handedOver);
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
        return new GrantOutcome(granted, queued, failed, receipt);
    }

    /**
     * The same pass with the two questions every payout site was asking in front of it folded in:
     * whether a reward should even be attempted with nobody there to receive it, and what it should
     * say paid it out.
     *
     * <p>Both are statements about the REWARD rather than about whoever is granting it, so asking
     * them here is what keeps them meaning one thing. The alternative is each payout site (or worse,
     * each handler) remembering to ask, which is how one of them stops asking and a flag quietly
     * means nothing on that path.
     *
     * @param playerOnline whether the receiving player is here right now; false drops the rewards
     *                     authored {@link #P_QUEUE_IF_OFFLINE} false before any handler sees them
     */
    @Nonnull
    public static GrantOutcome grantAll(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject,
                                        @Nonnull String sourceId, @Nonnull RewardKindRegistry kinds,
                                        boolean playerOnline,
                                        @Nullable BiConsumer<Subject, String> retryQueue,
                                        @Nonnull Consumer<String> warn) {
        return grantAll(stamped(deliverable(rewards, playerOnline, sourceId, warn), sourceId),
                subject, sourceId, kinds, retryQueue, warn);
    }

    /**
     * The rewards a pass should even attempt. Everything, when the player is here; when they are
     * not, everything EXCEPT the ones authored {@link #P_QUEUE_IF_OFFLINE} false, each dropped with
     * one warning naming what was skipped and why.
     *
     * <p>Package-private: the only caller is the {@code playerOnline} {@link #grantAll} overload
     * above, which folds it in front of {@link #stamped}. {@code stamped} stays public - a test
     * exercises it on its own to pin the source-stamping rule in isolation - but this one has no
     * caller that needs to ask the question by itself.
     */
    @Nonnull
    static List<RewardSpec> deliverable(@Nonnull List<RewardSpec> rewards, boolean playerOnline,
                                        @Nonnull String sourceId, @Nonnull Consumer<String> warn) {
        if (playerOnline) {
            return rewards;
        }
        List<RewardSpec> out = new ArrayList<>(rewards.size());
        for (RewardSpec spec : rewards) {
            if (spec == null) {
                continue;
            }
            if (spec.flagParam(P_QUEUE_IF_OFFLINE, false)) {
                out.add(spec);
            } else {
                warn.accept("[grant] " + sourceId + ": skipping a '" + spec.kind()
                        + "' reward, because the player is offline and it is authored"
                        + " queueIfOffline: false");
            }
        }
        return out;
    }

    /**
     * The rewards with the real payout source written onto them, so a log line and a
     * {@code {source}} placeholder name what actually paid out rather than the word the handler was
     * registered under. A reward that already names its own source keeps it.
     */
    @Nonnull
    public static List<RewardSpec> stamped(@Nonnull List<RewardSpec> rewards, @Nonnull String sourceId) {
        List<RewardSpec> out = new ArrayList<>(rewards.size());
        for (RewardSpec spec : rewards) {
            if (spec == null) {
                continue;
            }
            out.add(spec.param(P_SOURCE) == null ? spec.with(P_SOURCE, sourceId) : spec);
        }
        return out;
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
