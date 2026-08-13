package com.ziggfreed.common.dialogue.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What should follow a quest settling: the conversation to play and who to play it with, or the
 * reason there is none.
 *
 * <p>Built only by {@link QuestCompletionRouting}, never by a caller and never by an authored file.
 * A surface asks for one at the moment it just finished a hand-in, and either plays it or carries on
 * with its own refresh.
 *
 * @param questId    the quest that just settled, trimmed
 * @param dialogueId the conversation to play, or null unless the outcome is {@link Outcome#PLAY}
 * @param npcId      the character it is played with, or null unless the outcome is
 *                   {@link Outcome#PLAY}
 * @param outcome    why this quest settling does or does not lead into a conversation
 */
public record QuestHandOff(@Nonnull String questId, @Nullable String dialogueId,
                           @Nullable String npcId, @Nonnull Outcome outcome) {

    /**
     * Why this quest settling does or does not lead into a conversation.
     *
     * <p><b>This is a RESULT discriminator on a returned value, not a behaviour mode.</b> Nothing in
     * any JSON selects it, no codec reads or writes it, and no author ever types one of these names:
     * it is how a routing answer says which of its own branches it took, exactly as a grant outcome
     * or a parse result does. So the "express variation as orthogonal knobs, never a mode" rule does
     * not apply and there is nothing here to decompose - a new constant would mean a new REASON a
     * hand-off did not happen, which is precisely what an enum of reasons is for. Do not redesign it
     * into booleans; a caller wanting one question asks {@link #plays()}.
     */
    public enum Outcome {

        /** There is a conversation, a character to have it with, and a surface able to open it. */
        PLAY,

        /** The quest names no conversation. Most quests simply pay out. */
        NONE_AUTHORED,

        /**
         * Nobody is in front of the player. A quest log, a book, an admin command or an auto-claim
         * in the field has no character to speak the lines, so the beat is skipped rather than put
         * into some arbitrary NPC's mouth.
         */
        NO_NPC_CONTEXT,

        /**
         * A conversation is named but no registered host knows it - a typo, or a conversation
         * belonging to a mod this server does not run.
         */
        NO_HOST
    }

    public QuestHandOff {
        questId = questId == null ? "" : questId.trim();
        dialogueId = dialogueId == null || dialogueId.isBlank() ? null : dialogueId.trim();
        npcId = npcId == null || npcId.isBlank() ? null : npcId.trim();
        outcome = outcome == null ? Outcome.NONE_AUTHORED : outcome;
        if (outcome == Outcome.PLAY && (dialogueId == null || npcId == null)) {
            // The whole point of PLAY is that a host may use both without checking either. Reaching
            // here means the routing built one from an incomplete decision, which is a bug in this
            // package rather than anything a caller or an author can cause.
            throw new IllegalArgumentException("a playable hand-off needs both a dialogue and a character");
        }
    }

    /**
     * Is there a conversation to play, and everything needed to play it? True only for
     * {@link Outcome#PLAY}, where {@link #dialogueId()} and {@link #npcId()} are both non-null.
     */
    public boolean plays() {
        return outcome == Outcome.PLAY;
    }

    /** A hand-off that is not happening, and why. */
    @Nonnull
    public static QuestHandOff none(@Nonnull String questId, @Nonnull Outcome outcome) {
        return new QuestHandOff(questId, null, null,
                outcome == Outcome.PLAY ? Outcome.NONE_AUTHORED : outcome);
    }
}
