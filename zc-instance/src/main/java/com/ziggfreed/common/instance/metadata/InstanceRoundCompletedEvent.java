package com.ziggfreed.common.instance.metadata;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.instance.result.ResultKind;

/**
 * The ONE generic "a round is over" moment any minigame built on this library fires.
 *
 * <p><b>Why it is here rather than in each consumer.</b> Every consumer already has an outcome of
 * its own with its own vocabulary (Kweebec's {@code ESCAPED} / {@code CAUGHT} / {@code TIMED_OUT}),
 * and a third party wanting to react to "a round finished" would otherwise have to learn each of
 * them one at a time. This event carries the flat {@link RoundMetadata} envelope plus the two player
 * lists, so a listener reacts to any minigame's round - present or future, in this repo or not -
 * without a compile-time edge to the mod that produced it. A consumer keeps firing its own richer
 * event beside this one; the two are not alternatives.
 *
 * <p><b>{@code winners} is the whole outcome, expressed as people rather than as an enum.</b> On a
 * co-op win it equals {@code participants}; on a PvP win it is the winning team; on a loss or an
 * abort it is EMPTY. That is what makes {@link #isWin()} answerable without any listener having to
 * map a consumer's own outcome names, and it is why a "per winner" reward is a walk of one list
 * rather than a per-consumer rule.
 *
 * <p><b>Fire it on the instance world thread</b> (see {@link InstanceRounds#fireCompleted}).
 * Dispatch is synchronous on the calling thread, so a listener runs before the fire returns; a
 * listener that needs a {@code Store} hops with {@code world.execute} itself rather than assuming
 * which world it woke up on.
 *
 * <p>Both lists are copied on construction and handed out unmodifiable, so a listener cannot reach
 * back into the producer's round state and a producer cannot change what an earlier listener already
 * read.
 */
public final class InstanceRoundCompletedEvent implements IEvent<Void> {

    @Nonnull
    private final RoundMetadata metadata;
    @Nonnull
    private final List<UUID> participants;
    @Nonnull
    private final List<UUID> winners;

    /**
     * @param metadata     the flat telemetry envelope for the round. Its
     *                     {@link RoundMetadata#resultKind()} is expected to be one of the
     *                     {@link ResultKind} names ({@code WIN} / {@code LOSS} / {@code DRAW} /
     *                     {@code ABORT}) - build it with
     *                     {@link RoundMetadata.Builder#resultKind(ResultKind)} and it is by
     *                     construction. The field stays a free string because the envelope is a
     *                     pure-data integration payload, so a consumer with an outcome this library
     *                     has never heard of can still say what happened; a listener reading it
     *                     should treat an unrecognised tag as "some other outcome" rather than as a
     *                     loss.
     * @param participants every player who took part, by UUID. Copied; no element may be
     *                     {@code null} ({@code List.copyOf} rejects one with a
     *                     {@code NullPointerException}).
     * @param winners      the players who WON: equal to {@code participants} on a co-op win, the
     *                     winning team on PvP, EMPTY on a loss or an abort. Copied; no element may
     *                     be {@code null}, for the same reason.
     */
    public InstanceRoundCompletedEvent(@Nonnull RoundMetadata metadata,
                                       @Nonnull List<UUID> participants,
                                       @Nonnull List<UUID> winners) {
        this.metadata = metadata;
        this.participants = List.copyOf(participants);
        this.winners = List.copyOf(winners);
    }

    /** The flat, engine-free description of the round: which mod, which mode, which preset, how long. */
    @Nonnull
    public RoundMetadata metadata() {
        return metadata;
    }

    /** Every player who took part, unmodifiable. */
    @Nonnull
    public List<UUID> participants() {
        return participants;
    }

    /**
     * The players who won, unmodifiable: all of {@link #participants()} on a co-op win, the winning
     * team on PvP, empty on a loss or an abort.
     */
    @Nonnull
    public List<UUID> winners() {
        return winners;
    }

    /**
     * Did anybody win? Read straight off {@link #winners()} being non-empty, so there is exactly one
     * definition of a win and no listener has to interpret an outcome tag to get it.
     */
    public boolean isWin() {
        return !winners.isEmpty();
    }
}
