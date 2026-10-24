package com.ziggfreed.common.objectives.journal;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.objectives.questlist.CharacterQuestListing;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
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
     * What an accept did: whether the quest was taken on, and whether the settle right behind it
     * {@code finished} it at once (a standing value already met every step, so it paid out or parked
     * for collecting in the same press). A finished accept is announced by the engine's completion
     * notice alone, so a surface raises nothing of its own for it.
     */
    public record Accepted(boolean accepted, boolean finished) {

        /** The accept was refused, or there was nothing to accept. */
        public static final Accepted REFUSED = new Accepted(false, false);
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
        AtomicBoolean finished = new AtomicBoolean();
        boolean ok = Boolean.TRUE.equals(ProgressionRuntime.questScope().around(subject, s -> {
            boolean accepted = engine.canAccept(s, quest).allowed() && engine.accept(s, quest, siteId);
            if (accepted) {
                // A standing value can already meet every step (a level reached, an item carried):
                // finish it now rather than leaving a quest with nothing left to do still active.
                // Finished = it paid out now, or it parked to be collected (the settle answers null then).
                boolean paid = engine.trySettle(s, quest, siteId) != null;
                finished.set(paid || engine.status(s, quest) == QuestStatus.COMPLETED_UNCLAIMED);
            }
            return Boolean.valueOf(accepted);
        }));
        return ok ? new Accepted(true, finished.get()) : Accepted.REFUSED;
    }

    /**
     * Take a contract a board this character hosts has posted, through the BOARD's own accept
     * ({@code BoardEngine.accept} with the posting's slot), so every gate the board itself asks holds here
     * too: the board's, the band's or the contract's own entry gate, the contract's own, the slot's, the
     * period lock and whether it is already carried. The board is recorded as where it was taken, so the
     * contract hands in and collects at the board and at every character hosting it. Nothing is settled
     * behind it, as at the board.
     *
     * <p>{@code nowMs} is the clock reading the posting was read at (the listing's own,
     * {@link CharacterQuestListing#nowMs}), so a press near a turnover takes the contract against the same
     * period its draw came from, never the next period's lock.
     *
     * @return the board's answer: ok, or the refusal token a surface turns into words
     */
    @Nonnull
    public static BoardEngine.BoardCheck acceptPosting(@Nonnull Subject subject,
            @Nonnull CharacterQuestListing.Posting posting, long nowMs) {
        BoardEngine engine = CommerceEngines.boards();
        BoardEngine.BoardCheck check = ProgressionRuntime.questScope().around(subject,
                s -> engine.accept(s, posting.board(), posting.contract(), posting.slot(), nowMs));
        return check == null ? BoardEngine.BoardCheck.refused(BoardEngine.REASON_REFUSED) : check;
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
