package com.ziggfreed.common.npc;

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
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.dialogue.quest.QuestCompletionRouting;
import com.ziggfreed.common.dialogue.quest.QuestHandOff;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.subject.Subject;

/**
 * How to get an {@link NpcEncounter}: from the NPC a player just pressed F on, from an id a surface
 * already knows, or from the conversation currently open.
 *
 * <p>All three take the {@link DialogueQuests} seam the consumer already wired for its conversations,
 * because the questions an NPC panel asks about quests are the same ones a conversation asks - there
 * is no second seam to fill and no way for the two surfaces to disagree about what a player may hand
 * in.
 */
public final class NpcEncounters {

    private NpcEncounters() {
    }

    /**
     * The encounter with the NPC entity {@code npcRef}, whoever that turns out to be. The press-F
     * form: identity is resolved through the whole ladder, so a placed NPC, a role with an identity
     * overlay and a plain vanilla role all answer.
     */
    @Nonnull
    public static NpcEncounter at(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Ref<EntityStore> npcRef, @Nonnull DialogueQuests quests) {
        return new Encounter(store, playerRef, npcRef, NpcIdentities.npcIdOfEntity(store, npcRef), quests);
    }

    /**
     * The encounter with whoever {@code npcId} names. The surface form, for a page opened about a
     * character rather than at one - there is no entity, so nothing that needs one is answered from
     * one.
     */
    @Nonnull
    public static NpcEncounter at(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef,
            @Nullable String npcId, @Nonnull DialogueQuests quests) {
        return new Encounter(store, playerRef, null, npcId, quests);
    }

    /**
     * The encounter the open conversation is with. Its context id IS the character, so a dialogue
     * action or a consumer's own condition can ask everything a page can.
     */
    @Nonnull
    public static NpcEncounter at(@Nonnull DialogueContext ctx, @Nonnull DialogueQuests quests) {
        return new ContextEncounter(ctx, quests);
    }

    /** One per-quest, per-answered-id question, so the two readiness reads share one walk. */
    @FunctionalInterface
    private interface QuestQuestion {
        boolean ask(@Nonnull Subject subject, @Nonnull String questId, @Nullable String atId);
    }

    /** The shared body: everything both forms answer, given a way to resolve the player. */
    private abstract static class Base implements NpcEncounter {

        @Nonnull protected final DialogueQuests quests;
        @Nonnull private final String npcId;
        @Nonnull private final Set<String> answersTo;

        Base(@Nullable String npcId, @Nonnull DialogueQuests quests) {
            this.quests = quests;
            this.npcId = npcId == null ? "" : npcId.trim();
            // Resolved ONCE. Every question below would otherwise walk the alias set again, and these
            // are asked per row inside render loops.
            this.answersTo = this.npcId.isEmpty() ? Set.of()
                    : NpcIdentities.answerSetForPrimary(this.npcId);
        }

        @Override
        @Nonnull
        public String npcId() {
            return npcId;
        }

        @Override
        @Nonnull
        public Collection<String> answersTo() {
            return answersTo;
        }

        /** The player, built through the seam so a consumer's richer subject is what the engine sees. */
        @Nonnull
        protected abstract Subject subject();

        @Override
        @Nonnull
        public List<NpcOffer> offerableHere() {
            return answersTo.isEmpty() ? List.of() : NpcOfferProviders.offersAt(subject(), answersTo);
        }

        @Override
        public boolean anythingOfferedHere() {
            return !answersTo.isEmpty() && NpcOfferProviders.hasOffersAt(subject(), answersTo);
        }

        @Override
        public boolean readyHere(@Nonnull String questId) {
            return anyAnsweredId(questId, quests.reader()::resolvesTurnInAt);
        }

        @Override
        public boolean deliverableHere(@Nonnull String questId) {
            return anyAnsweredId(questId, quests.reader()::canDeliverTurnInAt);
        }

        /** Ask {@code question} about this quest at each id the character answers to, until one says yes. */
        private boolean anyAnsweredId(@Nonnull String questId, @Nonnull QuestQuestion question) {
            if (answersTo.isEmpty()) {
                return false;
            }
            Subject subject = subject();
            for (String id : answersTo) {
                if (question.ask(subject, questId, id)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean anythingDeliverableHere() {
            if (answersTo.isEmpty()) {
                return false;
            }
            Subject subject = subject();
            for (String id : answersTo) {
                if (quests.reader().hasDeliverableTurnInAt(subject, id)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean deliver(@Nonnull String questId) {
            if (answersTo.isEmpty()) {
                return false;
            }
            Subject subject = subject();
            for (String id : answersTo) {
                if (quests.turnIn(subject, questId, id)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        @Nonnull
        public QuestHandOff completionHandOff(@Nonnull String questId) {
            // The PRIMARY id, not whichever alias took the hand-in: the conversation's @self targets
            // and its header name the character the player is actually looking at.
            return QuestCompletionRouting.decide(questId, npcId(), quests);
        }
    }

    /** The engine-handle form: builds its subject and its credit from the store and the player ref. */
    private static final class Encounter extends Base {

        @Nonnull private final Store<EntityStore> store;
        @Nonnull private final Ref<EntityStore> playerRef;
        @Nullable private final Ref<EntityStore> npcRef;

        Encounter(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef,
                @Nullable Ref<EntityStore> npcRef, @Nullable String npcId, @Nonnull DialogueQuests quests) {
            super(npcId, quests);
            this.store = store;
            this.playerRef = playerRef;
            this.npcRef = npcRef;
        }

        @Override
        @Nonnull
        protected Subject subject() {
            PlayerRef ref = store.getComponent(playerRef, PlayerRef.getComponentType());
            Player player = store.getComponent(playerRef, Player.getComponentType());
            if (ref == null || player == null) {
                throw new IllegalStateException("no live player at this encounter");
            }
            return quests.subjectOf(store, playerRef, ref, player);
        }

        @Override
        public boolean creditTalk(@Nullable String qualifier) {
            if (npcId().isEmpty()) {
                return false;
            }
            PlayerRef ref = store.getComponent(playerRef, PlayerRef.getComponentType());
            if (ref == null) {
                return false;
            }
            return TalkCredits.credit(store, playerRef, ref, npcRef, npcId(), qualifier);
        }

        @Override
        public boolean playCompletion(@Nonnull String questId) {
            PlayerRef ref = store.getComponent(playerRef, PlayerRef.getComponentType());
            Player player = store.getComponent(playerRef, Player.getComponentType());
            if (ref == null || player == null) {
                return false;
            }
            return QuestCompletionRouting.handOff(questId, npcId(), quests, store, playerRef, ref, player);
        }
    }

    /**
     * The conversation form: the context already knows the player and how to enrich the subject.
     *
     * <p><b>It deliberately keeps {@code playCompletion}'s false default</b>, and that is a decision
     * rather than an omission: a conversation does not hand off to itself. A {@code TurnIn} beat
     * inside a dialogue is already on the screen the hand-off would open, and it routes onward with
     * {@code Goto}. It still answers {@link NpcEncounter#completionHandOff} through the base, so a
     * dialogue action that wants to know what would follow can ask.
     */
    private static final class ContextEncounter extends Base {

        @Nonnull private final DialogueContext ctx;

        ContextEncounter(@Nonnull DialogueContext ctx, @Nonnull DialogueQuests quests) {
            super(ctx.contextId(), quests);
            this.ctx = ctx;
        }

        @Override
        @Nonnull
        protected Subject subject() {
            return quests.subject(ctx);
        }

        @Override
        public boolean creditTalk(@Nullable String qualifier) {
            if (npcId().isEmpty()) {
                return false;
            }
            try {
                return TalkCredits.credit(ctx.store(), ctx.ref(), ctx.playerRef(), null, npcId(), qualifier);
            } catch (Throwable t) {
                // A context with no engine handles can read a conversation but cannot credit one.
                return false;
            }
        }
    }
}
