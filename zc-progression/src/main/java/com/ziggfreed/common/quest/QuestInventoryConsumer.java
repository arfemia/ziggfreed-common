package com.ziggfreed.common.quest;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * Actually takes the items a hand-in asks for. The write half of a hand-in beside the read-only
 * {@link QuestPossessionProbe}.
 *
 * <p><b>Partial delivery is a feature.</b> Return how many were REALLY taken, which may be fewer
 * than asked for; the engine credits exactly that many and leaves the rest of the objective owing,
 * so a player can chip away at a big hand-in over several visits. Return {@code 0} when nothing was
 * taken - and take nothing at all in that case, since the engine trusts the number.
 */
@FunctionalInterface
public interface QuestInventoryConsumer {

    /** Takes nothing: what an engine with no inventory access should do. */
    QuestInventoryConsumer NONE = (subject, itemId, max) -> 0;

    /**
     * Take up to {@code max} of {@code itemId} from this player and return how many were taken
     * (0..{@code max}). Must not take more than it reports.
     */
    int take(@Nonnull Subject subject, @Nonnull String itemId, int max);
}
