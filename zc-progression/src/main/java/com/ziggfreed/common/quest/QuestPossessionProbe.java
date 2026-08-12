package com.ziggfreed.common.quest;

import java.util.function.BiPredicate;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * Asks whether a player could hand over {@code count} of {@code itemId} right now, WITHOUT taking
 * anything. It is the read half of a hand-in; {@link QuestInventoryConsumer} is the write half, and
 * they are separate so a surface can offer a hand-in only when it will actually succeed.
 *
 * <p>Both defaults fail closed - an engine with no probe wired refuses item hand-ins rather than
 * completing them for free.
 */
@FunctionalInterface
public interface QuestPossessionProbe {

    /** Refuses everything: what an engine with no inventory access should do. */
    QuestPossessionProbe NONE = (subject, itemId, count) -> false;

    /** Accepts everything: for listing and ranking, where the inventory is deliberately ignored. */
    QuestPossessionProbe ANY = (subject, itemId, count) -> true;

    /** Could this player give up {@code count} of {@code itemId} right now? */
    boolean holds(@Nonnull Subject subject, @Nonnull String itemId, int count);

    /**
     * Adapt a subject-blind predicate, for a consumer whose inventory access is already bound to one
     * player (a test fixture, a single-player probe built at the call site).
     */
    @Nonnull
    static QuestPossessionProbe ofPredicate(@Nonnull BiPredicate<String, Integer> predicate) {
        return (subject, itemId, count) -> predicate.test(itemId, count);
    }
}
