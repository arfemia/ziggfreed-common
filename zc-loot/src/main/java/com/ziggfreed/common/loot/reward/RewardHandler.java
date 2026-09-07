package com.ziggfreed.common.loot.reward;

import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * What a registered reward kind actually DOES, plus how to try it again later if it could not be
 * done now.
 *
 * <p>{@link #grant} may throw - that is expected, not exceptional. A payout can fail for reasons
 * nobody controls (the player has just gone offline, an inventory filled up mid-claim), and
 * {@link RewardGrants} isolates each reward so one failure never costs the player the others.
 *
 * <p>{@link #retryCommand} is how a failure stops being a loss: return a console command that would
 * deliver the same thing later and the engine hands it to the consumer's retry queue. Return null
 * when the reward genuinely cannot be replayed, and it is reported as lost rather than pretended
 * away.
 *
 * <p>{@link #grant(RewardSpec, Subject, String, Consumer)} is how a payout says what it ACTUALLY
 * handed over. Most kinds hand over exactly what the reward said, and the default reports the spec
 * itself; a kind that decides at grant time (a rolled table) reports what landed instead, so a toast
 * raised after the payout can list the items rather than the table's name.
 */
public interface RewardHandler {

    /** Deliver this reward to this player now. May throw; the caller isolates and reports it. */
    void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception;

    /**
     * The same grant, told WHERE the payout came from. The default simply drops the label, so a
     * handler that does not care implements only {@link #grant(RewardSpec, Subject)} and nothing
     * changes for it.
     *
     * <p>Override it when the handler's own output names its source - a log line, or a command whose
     * placeholders want the id of the quest or the shop that paid. Without it such a handler can only
     * name the label it was REGISTERED under, so every quest in the game reports the same "quest".
     * The same {@code sourceId} reaches {@link #retryCommand}, so a live payout and a replayed one
     * agree about where they came from.
     */
    default void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId)
            throws Exception {
        grant(spec, subject);
    }

    /**
     * The same grant, handed a RECEIPT to write what was actually handed over onto. This is the
     * form {@link RewardGrants} calls, and the default grants through the labelled form above and
     * then reports the spec itself, because for most kinds the spec IS what was handed over: an
     * item is that item, a currency payout is that amount, a command is that command.
     *
     * <p>Override it when the handler only learns what it is paying out while paying it: a kind
     * that rolls a table reports one entry per thing that landed, and nothing at all for an empty
     * roll, so a toast raised after the payout lists what the player actually got. Report only
     * what genuinely reached the player - a receipt is read as a statement of fact - and never
     * report before the handing over is done, since a grant that throws afterwards is retried or
     * reported lost, and whatever it wrote onto the receipt is discarded with it.
     *
     * @param receipt where each thing handed over is reported, in the order it landed; the
     *                caller keeps it only when this grant returns normally
     */
    default void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
                       @Nonnull Consumer<RewardSpec> receipt) throws Exception {
        grant(spec, subject, sourceId);
        receipt.accept(spec);
    }

    /**
     * A console command that would deliver the same reward on a later attempt, or null when there is
     * no replayable form. {@code sourceId} labels where the payout came from, for logs and for any
     * placeholder the command wants.
     */
    @Nullable
    default String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                                @Nonnull String sourceId) {
        return null;
    }
}
