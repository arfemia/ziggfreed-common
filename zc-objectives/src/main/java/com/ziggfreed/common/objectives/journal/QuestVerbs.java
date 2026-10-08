package com.ziggfreed.common.objectives.journal;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * The two things a surface does to a quest at a place, over THE shared progression runtime: take it
 * on, and hand it in. The objective book, the NPC quest page and the library's own conversation
 * binding all act through here, so a quest taken from a book, at a giver's list or in the middle of
 * a conversation is taken the same way and fires the same moments.
 *
 * <p>Every call runs inside the registered {@link ProgressionCallScope}, which is what lets the
 * owning mod's listeners hear a write made from a library surface exactly as they hear one from
 * that mod's own menu. What a surface says about the result (a toast, a repaint, a feedback seam) is
 * that surface's; the engine calls and their order live here once.
 *
 * <p>World thread: both verbs write the player's progress.
 */
public final class QuestVerbs {

    /**
     * What an accept did: whether the quest was taken on, and what the settle right behind it paid.
     * {@code settled} is non-null only when a standing value already met every step and the quest
     * paid out on the spot, so an announcement can list what was actually handed over; it is null
     * for an ordinary accept and for one that finished but parked for collecting.
     */
    public record Accepted(boolean accepted, @Nullable RewardGrants.GrantOutcome settled) {

        /** The accept was refused, or there was nothing to accept. */
        public static final Accepted REFUSED = new Accepted(false, null);
    }

    private QuestVerbs() {
    }

    /**
     * Take {@code quest} on for {@code subject} at {@code siteId}, through the same accept check
     * every surface asks, then settle it at once when what the player already has finished it.
     *
     * <p>{@code siteId} is where the player is: the character a conversation or a giver's list is
     * with, null from a book or a log that is nowhere in particular. The engine records it as where
     * the quest was taken, and the settle behind the accept is asked there too, so a quest that may
     * be collected where it was given pays out on the spot rather than parking for a walk back to
     * the same character.
     */
    @Nonnull
    public static Accepted acceptAt(@Nonnull Subject subject, @Nonnull Quest quest, @Nullable String siteId) {
        QuestEngine engine = ProgressionRuntime.quests();
        AtomicReference<RewardGrants.GrantOutcome> settled = new AtomicReference<>();
        boolean ok = Boolean.TRUE.equals(ProgressionRuntime.questScope().around(subject, s -> {
            boolean accepted = engine.canAccept(s, quest).allowed() && engine.accept(s, quest, siteId);
            if (accepted) {
                // A standing value can already meet every step (a level reached, an item carried):
                // finish it now rather than leaving a quest with nothing left to do still active.
                settled.set(engine.trySettle(s, quest, siteId));
            }
            return Boolean.valueOf(accepted);
        }));
        return ok ? new Accepted(true, settled.get()) : Accepted.REFUSED;
    }

    /**
     * Hand in EVERY outstanding step of {@code quest} that {@code atId} takes, in one press, and
     * answer what that did. Pass the id the place answered under ({@code CharacterQuestListing}'s
     * {@code turnInHere} finds it): a hand-in that finishes a quest at its own collection site pays
     * out there and then, while the same hand-in from nowhere parks it.
     *
     * <p>A quest that parks stays parked: collecting is the player's own press, which a giver's list
     * makes right behind a hand-in on its own button and a conversation leaves to that list.
     */
    @Nonnull
    public static QuestEngine.TurnInOutcome handInAt(@Nonnull Subject subject, @Nonnull Quest quest,
            @Nullable String atId) {
        QuestEngine engine = ProgressionRuntime.quests();
        QuestEngine.TurnInOutcome handed = ProgressionRuntime.questScope()
                .around(subject, s -> engine.tryAllTurnIns(s, quest, atId));
        return handed == null ? QuestEngine.TurnInOutcome.NOTHING : handed;
    }
}
