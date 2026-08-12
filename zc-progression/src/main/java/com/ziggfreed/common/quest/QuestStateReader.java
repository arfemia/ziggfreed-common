package com.ziggfreed.common.quest;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.subject.Subject;

/**
 * THE quest-facing surface for a CONVERSATION - the read-only questions a dialogue asks before it
 * decides which line to show. It answers what a player's quest looks like right now and nothing
 * else: no accept, no hand-in, no claim, no catalogue.
 *
 * <p><b>A dialogue must never see {@link QuestEngine}.</b> That is the entire reason this interface
 * exists. The engine is a mutating runtime, and a condition that can reach it can accept a quest
 * while merely rendering an option - so the module that owns conversations depends on this narrow
 * read seam instead, and gets a compile error rather than a temptation. Keep it narrow: a new method
 * here has to be a question a dialogue genuinely asks, and it has to be one the quest runtime can
 * answer on its own.
 *
 * <p><b>What is deliberately NOT here: "does this place have anything to OFFER?"</b> Which quests a
 * given place hands out is an authoring-layer association plus a gate pass, neither of which the
 * runtime holds - the runtime knows what a player has STARTED, not who would have given it to them.
 * A consumer that needs that question answers it above the engine, where the catalogue and the gates
 * live, and registers its own condition for it.
 *
 * <p>Every method is total and side-effect free. An unknown quest id is not an error: a status read
 * reports {@link QuestStatus#NOT_STARTED} (a malformed condition should hide nothing), while a
 * readiness read fails CLOSED (a positive gate must never open on a typo).
 */
public interface QuestStateReader {

    /**
     * What this quest EFFECTIVELY is for this player right now - a finished repeatable reads as
     * on-cooldown or offerable-again as its clock decides, never as a permanent completion. Unknown
     * ids read {@link QuestStatus#NOT_STARTED}.
     */
    @Nonnull
    QuestStatus status(@Nonnull Subject subject, @Nonnull String questId);

    /**
     * This player's progress on one step, or null when nothing is recorded for it (they never
     * started the quest, the step is not theirs, or it has not moved yet).
     */
    @Nullable
    ObjectiveProgressState objectiveProgress(@Nonnull Subject subject, @Nonnull String questId,
                                             @Nonnull String objectiveId);

    /**
     * Every quest this player is carrying or has finished but not collected, by id. The set a
     * conversation looks through when it wants to say something about what is in progress.
     */
    @Nonnull
    List<String> activeAndUnclaimedIds(@Nonnull Subject subject);

    /**
     * Can this player hand THIS quest in here, right now, in full? True only when the quest is
     * genuinely active, its outstanding step resolves at {@code atId}, and the player is carrying
     * everything that step asks for.
     *
     * <p>The possession check is what makes this safe to OFFER a hand-in on: without it a
     * conversation shows "here, take it" and then silently does nothing. Unknown quest id, blank
     * {@code atId}, or a partial stack all read false.
     */
    boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull String questId, @Nullable String atId);

    /**
     * The any-quest form of {@link #canDeliverTurnInAt}: does this player have SOMETHING they can
     * hand in here right now? Lets one option say "I have returned" for any quest, present or
     * future, with no per-quest wiring.
     */
    boolean hasDeliverableTurnInAt(@Nonnull Subject subject, @Nullable String atId);

    /**
     * Does this quest's outstanding step RESOLVE here at all - carrying the goods or not?
     *
     * <p>The weaker half of {@link #canDeliverTurnInAt}, and the difference between "come back to me
     * when you have them" and "here, take these". A surface that only wants to mark a character as
     * the place this quest is going shows the marker on THIS, while the button that actually completes
     * it asks the possession-aware question - so a player is pointed at the right NPC without being
     * offered a hand-in that would silently do nothing.
     *
     * <p>Defaults to the possession-aware answer, which is always SAFE (it can only under-report a
     * destination, never offer an impossible hand-in) and is the honest answer for a runtime that
     * cannot tell the two apart. Override it wherever a step can resolve at a character without
     * anything being carried - a "go and speak to them" step is exactly that shape.
     */
    default boolean resolvesTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                                     @Nullable String atId) {
        return canDeliverTurnInAt(subject, questId, atId);
    }
}
