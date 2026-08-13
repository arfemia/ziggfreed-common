package com.ziggfreed.common.dialogue.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * What follows a quest settling: the ONE policy deciding whether a conversation plays, and with whom.
 *
 * <p>A quest may name the conversation its giver has once it is done. Whether that conversation
 * actually plays is not a fact about the quest and not a fact about whichever screen the player
 * happened to finish it on - it depends on there being somebody in front of them and somebody able to
 * open it. Left to each UI, that becomes several slightly different answers: a quest list that opens
 * the giver's reaction, a book that opens it with nobody there, an admin command that opens it for a
 * player standing in a field. So the decision lives here, once, and a surface HOSTS it (see
 * {@link QuestDialogueHost}) rather than owning it.
 *
 * <p><b>The policy, in order:</b>
 * <ol>
 *   <li>the quest names no conversation -> {@link QuestHandOff.Outcome#NONE_AUTHORED}. Most quests
 *       simply pay out.</li>
 *   <li>there is no character in front of the player -> {@link QuestHandOff.Outcome#NO_NPC_CONTEXT}.
 *       A completion conversation is a conversation WITH somebody: a quest log, an objective book, an
 *       admin command or a claim out in the field has nobody to speak the lines, and picking one
 *       would put words in the mouth of an NPC the player is not standing at.</li>
 *   <li>no registered host knows the conversation -> {@link QuestHandOff.Outcome#NO_HOST}. Covers a
 *       typo and a conversation owned by a mod this server does not run. Skipping is the only safe
 *       answer: the alternative is a caller that has already returned and a screen that never
 *       repaints.</li>
 *   <li>otherwise {@link QuestHandOff.Outcome#PLAY}, carrying the conversation and the character.</li>
 * </ol>
 *
 * <p><b>It deliberately does NOT check that the quest settled.</b> The CALLER fires this at the moment
 * it owns - a hand-in that completed, a reward claimed - because only the caller knows which moment it
 * just finished; this owns what FOLLOWS. Re-reading a status here would need a subject and would make
 * the routing a second authority on completion, free to disagree with the first.
 *
 * <p>World thread.
 */
public final class QuestCompletionRouting {

    private QuestCompletionRouting() {
    }

    /**
     * The decision alone. No screen is touched, so a surface may ask before it commits to a path (a
     * button that renders differently when a conversation follows, a validator, a test).
     *
     * @param questId            the quest that just settled
     * @param npcContextId       the character the player is at, or null when nobody is
     * @param authoredDialogueId the conversation the quest names, or null when it names none
     */
    @Nonnull
    public static QuestHandOff decide(@Nonnull String questId, @Nullable String npcContextId,
            @Nullable String authoredDialogueId) {

        if (authoredDialogueId == null || authoredDialogueId.isBlank()) {
            return QuestHandOff.none(questId, QuestHandOff.Outcome.NONE_AUTHORED);
        }
        if (npcContextId == null || npcContextId.isBlank()) {
            return QuestHandOff.none(questId, QuestHandOff.Outcome.NO_NPC_CONTEXT);
        }
        if (!QuestDialogueHosts.knows(authoredDialogueId)) {
            return QuestHandOff.none(questId, QuestHandOff.Outcome.NO_HOST);
        }
        return new QuestHandOff(questId, authoredDialogueId, npcContextId, QuestHandOff.Outcome.PLAY);
    }

    /**
     * The same decision, reading the conversation the quest names through the seam the consumer
     * already wired for its dialogues.
     */
    @Nonnull
    public static QuestHandOff decide(@Nonnull String questId, @Nullable String npcContextId,
            @Nonnull DialogueQuests quests) {
        String authored;
        try {
            authored = quests.completionDialogueOf(questId);
        } catch (Throwable t) {
            // A catalogue that cannot answer is a catalogue that names no conversation. A hand-in
            // must never fail because the beat after it could not be looked up.
            SafeLog.warn("[quest-dialogue] reading the completion conversation for '" + questId
                    + "' failed: " + t.getMessage());
            authored = null;
        }
        return decide(questId, npcContextId, authored);
    }

    /**
     * Decide AND drive it.
     *
     * @return true when a host took over the screen, in which case the caller must NOT render
     *         anything else; false means nothing happened and the caller keeps its own refresh
     */
    public static boolean handOff(@Nonnull String questId, @Nullable String npcContextId,
            @Nonnull DialogueQuests quests, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull Player player) {

        try {
            QuestHandOff decision = decide(questId, npcContextId, quests);
            return decision.plays() && QuestDialogueHosts.open(decision, store, ref, playerRef, player);
        } catch (Throwable t) {
            // This sits directly inside a click handler. A throw here would take the hand-in's own
            // response down with it, which costs the player their screen over a cosmetic beat.
            SafeLog.warn("[quest-dialogue] handing '" + questId + "' off failed: " + t.getMessage());
            return false;
        }
    }
}
