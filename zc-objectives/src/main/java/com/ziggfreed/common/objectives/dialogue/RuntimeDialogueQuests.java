package com.ziggfreed.common.objectives.dialogue;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.objectives.book.BookVerbs;
import com.ziggfreed.common.objectives.book.ObjectiveBookPages;
import com.ziggfreed.common.objectives.journal.QuestVerbs;
import com.ziggfreed.common.objectives.questlist.CharacterQuestListing;
import com.ziggfreed.common.objectives.questlist.NpcQuestPages;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's own quest binding for conversations: every quest-aware line ({@code QuestState},
 * {@code ReadyToTurnIn}, {@code Accept}, {@code TurnIn}, a {@code Start} quest row) read and acted
 * through the ONE shared progression runtime, so a giver written against the shared vocabulary
 * works on a server running this jar and nothing else.
 *
 * <p><b>Installed beneath the quest slot, never in it.</b> The library's setup puts this in the
 * dialogue engine's default slot ({@code DialogueEngine.installDefaultQuests}), which answers only
 * while no consumer has installed a runtime of its own. A consumer still calls
 * {@code DialogueEngine.installQuests} exactly as before and is in charge the moment it does,
 * whichever setup ran first.
 *
 * <p><b>Every answer is one another surface already gives.</b> The state reads are the engine's
 * own; the player is the runtime's subject for them, as on the book and a giver's list; the ids a
 * character answers to are the giver's list's; an accept and a hand-in are {@link QuestVerbs}, the
 * same verbs those two surfaces press; and the conversation a quest hands off to is the one its
 * folded definition names. Nothing here is a second reading of any of them.
 *
 * <p><b>Extending it.</b> A consumer with one rule of its own to add extends this class and
 * overrides that one method, keeping every other answer the library's, and installs the result
 * through {@code installQuests}. Overriding is per method and nothing here calls an overridable
 * method of its own except through the interface's defaults ({@code subject(ctx)} asks
 * {@link #subjectOf}), so an override changes exactly what it names.
 */
public class RuntimeDialogueQuests implements DialogueQuests {

    /** The binding the library installs; it holds nothing, every answer is read live. */
    public static final RuntimeDialogueQuests INSTANCE = new RuntimeDialogueQuests();

    /** For a consumer extending this binding; the library itself uses {@link #INSTANCE}. */
    protected RuntimeDialogueQuests() {
    }

    /**
     * The shared quest engine, read through its narrow read face: a conversation sees what a
     * player's quest looks like, never a way to change it.
     */
    @Override
    @Nonnull
    public QuestStateReader reader() {
        return ProgressionRuntime.quests();
    }

    /**
     * The player as the runtime knows them, the same subject the book and a giver's list read and
     * write through, so a consumer that brings its own subject source is the one asked here too.
     * A subject built locally instead would read neutral through that consumer's store and have
     * every write dropped.
     *
     * <p>Asked about the player's OWN entity whatever {@code ref} names: a surface that is not a
     * conversation can hand in an anchor or a character. With no runtime subject for this player
     * the interface's own answer stands, which reads nothing and refuses every write.
     */
    @Override
    @Nonnull
    public Subject subjectOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        Subject live = runtimeSubject(store, ownReference(player, ref));
        return live != null ? live : DialogueQuests.super.subjectOf(store, ref, player);
    }

    /**
     * Every id the character answers to, its own first: the same answer set a giver's quest list
     * reads ({@code NpcQuestPageDeps.answerSetOrOwn}), so a consumer's identity layer, or the
     * placement and identity assets when there is none, decides both alike.
     */
    @Override
    @Nonnull
    public Collection<String> answersTo(@Nullable String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return List.of();
        }
        return NpcQuestPages.resolvedDeps().answerSetOrOwn(contextId);
    }

    /**
     * Take the quest on where the conversation is, through {@link QuestVerbs#acceptAt}, and
     * announce it through the same seam an accept from the objective book announces through, so
     * a consumer that words its accepts once is heard here too. True only when it started.
     */
    @Override
    public boolean accept(@Nonnull Subject subject, @Nonnull String questId, @Nullable String siteId) {
        Quest quest = BookVerbs.quest(questId);
        if (quest == null) {
            return false;
        }
        QuestVerbs.Accepted accepted = QuestVerbs.acceptAt(subject, quest, siteId);
        if (!accepted.accepted()) {
            return false;
        }
        announceAccepted(subject, quest, accepted.settled());
        return true;
    }

    /**
     * Hand in what this character is owed, at the id it answered under. True only when the hand-in
     * FINISHED the quest, whether it paid out here or parked for collecting; a step that merely
     * advanced answers false, so the line carries on through the rest of the answer set.
     *
     * <p>Nothing is collected here: a quest whose rewards wait to be collected stays parked, which
     * is what sends the player to the character's quest list with Collect in front of them.
     */
    @Override
    public boolean turnIn(@Nonnull Subject subject, @Nonnull String questId, @Nullable String atId) {
        Quest quest = BookVerbs.quest(questId);
        QuestEngine engine = ProgressionRuntime.quests();
        if (quest == null || engine.status(subject, quest) != QuestStatus.ACTIVE) {
            return false;
        }
        Set<String> here = atId == null || atId.isBlank() ? Set.of() : Set.of(atId);
        CharacterQuestListing.TurnIn owed = new CharacterQuestListing(engine, subject, here).turnInHere(quest);
        if (owed == null) {
            return false;
        }
        return QuestVerbs.handInAt(subject, quest, owed.atId()).creditedAny()
                && engine.status(subject, quest) != QuestStatus.ACTIVE;
    }

    /** The conversation the quest's folded definition names, or null when it names none. */
    @Override
    @Nullable
    public String completionDialogueOf(@Nonnull String questId) {
        // The fold rides the runtime's build, so build it first: a pool read before that is empty.
        ProgressionRuntime.ensureBuilt();
        QuestDefinition definition = ProgressionDefaults.questPool().definition(questId);
        return definition == null ? null : definition.completionDialogue();
    }

    // ==================== internals ====================

    /** The runtime's subject for the entity at {@code at}, or null when it has none to give. */
    @Nullable
    private static Subject runtimeSubject(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> at) {
        if (at == null) {
            return null;
        }
        try {
            return ProgressionRuntime.subjects().questSubject(store, at);
        } catch (Throwable unreadable) {
            // A subject source that cannot answer leaves the interface's own answer to stand.
            return null;
        }
    }

    /** The player's own entity, else {@code fallback} while it is still live, else null. */
    @Nullable
    private static Ref<EntityStore> ownReference(@Nullable Player player, @Nullable Ref<EntityStore> fallback) {
        try {
            PlayerRef playerRef = player == null ? null : PlayerAccess.playerRef(player);
            Ref<EntityStore> own = playerRef == null ? null : playerRef.getReference();
            if (own != null && own.isValid()) {
                return own;
            }
        } catch (Throwable stale) {
            // A player whose reference cannot be read is asked about through the ref handed in.
        }
        return fallback != null && fallback.isValid() ? fallback : null;
    }

    /**
     * The accept announcement, through the objective book's {@code ActionFeedback} seam: the one
     * place a consumer says how an accept made on a library surface sounds. Guarded, because a
     * consumer's feedback failing costs its own moment, never the accept that earned it.
     */
    private static void announceAccepted(@Nonnull Subject subject, @Nonnull Quest quest,
            @Nullable RewardGrants.GrantOutcome settled) {
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        try {
            ObjectiveBookPages.resolvedDeps().actionFeedback()
                    .accepted(quest, ref.getStore(), ref, player, settled);
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] the accept announcement for '" + quest.id() + "' failed: " + t.getMessage());
        }
    }
}
