package com.ziggfreed.common.npc;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.quest.QuestHandOff;
import com.ziggfreed.common.quest.NpcOffer;

/**
 * Everything a surface needs to know about the character a player is standing at, obtained once.
 *
 * <p>Any page, panel or conversation that wants to be an NPC surface has the same handful of
 * questions: who is this, what are they offering, is anything of mine ready to hand in here, and can
 * I do it. Answering those by hand means knowing about alias sets, which of several readiness checks
 * is the possession-aware one, and where credit goes - none of which is the surface's business. An
 * encounter answers all of them, resolves the character's answer set ONCE instead of per question,
 * and is cheap enough to build per render.
 *
 * <p>Built through {@link NpcEncounters}. Every method is a read except the last two, and both of
 * those say whether anything actually happened rather than assuming it did.
 *
 * <p>World thread.
 */
public interface NpcEncounter {

    /** The character's PRIMARY id: what they are, not merely what they respond to. */
    @Nonnull
    String npcId();

    /** Every id this character answers to, primary first. */
    @Nonnull
    Collection<String> answersTo();

    /**
     * Everything on offer here, from every registered provider: available and locked alike, so a
     * surface can show a locked quest with its reason instead of hiding what the player should go and
     * work towards.
     */
    @Nonnull
    List<NpcOffer> offerableHere();

    /** Is anything at all available to take on here? The cheap read for a marker or a greeting line. */
    boolean anythingOfferedHere();

    /**
     * Does this quest's outstanding step resolve HERE - carrying the goods or not? The read behind a
     * marker or a "this is where you are going" hint.
     */
    boolean readyHere(@Nonnull String questId);

    /**
     * Can the player actually complete this quest here, right now - carrying whatever it asks for?
     * The difference from {@link #readyHere} is the whole reason both exist: a hand-in button that
     * silently does nothing is worse than no button, while a destination marker that vanishes because
     * the player is not carrying the goods yet is worse than useless.
     *
     * <p>A quest runtime that cannot tell the two apart answers both with the possession-aware one,
     * which under-reports a destination rather than offering an impossible hand-in.
     */
    boolean deliverableHere(@Nonnull String questId);

    /** Is ANY quest deliverable here? */
    boolean anythingDeliverableHere();

    /**
     * May this quest be completed AT this character at all - the SITE question, asked separately from
     * whether the player has finished it or is carrying the goods.
     *
     * <p>It is a REFUSAL gate rather than a readiness read, and the two are not the same question: a
     * quest that says "report back to the quartermaster" is finished and fully carried while standing
     * at the guide, and only this answers no. It is what the routing surface asks before sending a
     * player somewhere to hand something in.
     *
     * <p>A DEFAULT so a fourth party's own encounter stays source-compatible, answering the same
     * permissive yes the quest read seam does - a quest naming no place is the great majority, and a
     * stub that refused would hide every hand-in in the game.
     */
    default boolean canCompleteHere(@Nonnull String questId) {
        return true;
    }

    /** Hand this quest in. False when it did not go through, so a caller never reports a phantom. */
    boolean deliver(@Nonnull String questId);

    /**
     * Credit this conversation, alias set included. False when there was nothing to credit or the
     * re-trigger window had already taken this one.
     *
     * <p>For a UI surface this is the deliberate equivalent of a conversation's {@code MarkTalked}
     * beat: it credits because the surface decided a beat happened, never because a page opened.
     */
    boolean creditTalk(@Nullable String qualifier);

    /**
     * What conversation should follow this quest settling HERE, if any: the shared decision, asked on
     * this character rather than on an id the caller had to resolve itself.
     *
     * <p>Defaults to {@link QuestHandOff.Outcome#NO_NPC_CONTEXT}, so a fourth party's own encounter
     * implementation stays source-compatible and skips the beat rather than guessing at one.
     */
    @Nonnull
    default QuestHandOff completionHandOff(@Nonnull String questId) {
        return QuestHandOff.none(questId, QuestHandOff.Outcome.NO_NPC_CONTEXT);
    }

    /**
     * Decide and drive it. True when a host took over the screen, in which case the caller must NOT
     * render anything else.
     *
     * <p>Defaults to false: an encounter built without engine handles has no screen to hand over.
     */
    default boolean playCompletion(@Nonnull String questId) {
        return false;
    }
}
