package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.DialogueFlagStore;
import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.dialogue.quest.QuestDialogueHost;
import com.ziggfreed.common.dialogue.quest.QuestDialogueHosts;
import com.ziggfreed.common.dialogue.quest.QuestHandOff;
import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The at-NPC surface a fourth party consumes: one value that answers every question about the
 * character in front of the player.
 *
 * <p>What is worth pinning is the WIRING, not the answers - that the encounter resolves the alias set
 * from the identity authority rather than from whatever the caller passed, that it walks that set for
 * every quest question so a hand-in works wherever the giver stands, and that the destination read and
 * the completion read stay separate. Each of those is a way a surface could look right while quietly
 * only working for a character with one name.
 */
class NpcEncountersTest {

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "Tester");

    /** A context with no engine handles: the off-world case every accessor here is guarded for. */
    private static final class BareContext implements DialogueContext {

        @Nullable private final String contextId;

        BareContext(@Nullable String contextId) {
            this.contextId = contextId;
        }

        @Override @Nonnull public Store<EntityStore> store() {
            throw new IllegalStateException("no store here");
        }

        @Override @Nonnull public Ref<EntityStore> ref() {
            throw new IllegalStateException("no ref here");
        }

        @Override @Nonnull public PlayerRef playerRef() {
            throw new IllegalStateException("no player ref here");
        }

        @Override @Nonnull public Player player() {
            throw new IllegalStateException("no player here");
        }

        @Override @Nullable public String contextId() {
            return contextId;
        }

        @Override @Nonnull public DialogueFlagStore flags() {
            return new DialogueFlagStore() {
                @Override public boolean has(@Nonnull String flag) { return false; }

                @Override public void set(@Nonnull String flag) { }
            };
        }

        @Override @Nullable public <T> T payload(@Nonnull Class<T> type) {
            return null;
        }
    }

    /** Records every (quest, atId) pair each read was asked about, and answers from a fixed script. */
    private static final class ScriptedQuests implements DialogueQuests {

        final List<String> resolvesAsked = new ArrayList<>();
        final List<String> deliverAsked = new ArrayList<>();
        final List<String> turnInAsked = new ArrayList<>();

        /** {@code atId} that accepts a full hand-in; everything else only RESOLVES there. */
        @Nullable String deliverableAt;

        /** The conversation the catalogue says follows every quest here, or none. */
        @Nullable String completionDialogue;

        private final QuestStateReader reader = new QuestStateReader() {

            @Override @Nonnull public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
                return QuestStatus.ACTIVE;
            }

            @Override @Nullable public ObjectiveProgressState objectiveProgress(@Nonnull Subject subject,
                    @Nonnull String questId, @Nonnull String objectiveId) {
                return null;
            }

            @Override @Nonnull public List<String> activeAndUnclaimedIds(@Nonnull Subject subject) {
                return List.of();
            }

            @Override public boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                    @Nullable String atId) {
                deliverAsked.add(atId);
                return atId != null && atId.equals(deliverableAt);
            }

            @Override public boolean hasDeliverableTurnInAt(@Nonnull Subject subject, @Nullable String atId) {
                return atId != null && atId.equals(deliverableAt);
            }

            @Override public boolean resolvesTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                    @Nullable String atId) {
                resolvesAsked.add(atId);
                return true;
            }
        };

        @Override @Nonnull public QuestStateReader reader() {
            return reader;
        }

        @Override @Nonnull public Subject subject(@Nonnull DialogueContext ctx) {
            return PLAYER;
        }

        @Override public boolean turnIn(@Nonnull Subject subject, @Nonnull String questId, @Nullable String atId) {
            turnInAsked.add(atId);
            return atId != null && atId.equals(deliverableAt);
        }

        @Override @Nullable public String completionDialogueOf(@Nonnull String questId) {
            return completionDialogue;
        }
    }

    /** A host that knows one conversation and records the character each hand-off named. */
    private static final class ScriptedHost implements QuestDialogueHost {

        private final String knownId;
        final List<String> openedAt = new ArrayList<>();

        ScriptedHost(@Nonnull String knownId) {
            this.knownId = knownId;
        }

        @Override public boolean knows(@Nonnull String dialogueId) {
            return knownId.equals(dialogueId);
        }

        @Override public boolean open(@Nonnull QuestHandOff handOff, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull Player player) {
            openedAt.add(handOff.npcId());
            return true;
        }
    }

    /** One placement standing for a character that also answers to a shared name. */
    private static void loadAliasedGuide() {
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of(
                "guide_wilds", NpcPlacementAsset.of("guide_wilds", null,
                        NpcPlacementAsset.Identity.of(null, "guide_wilds",
                                new String[] {"adventurers_guide"}),
                        null, null, null, null, null, null)));
    }

    @BeforeEach
    @AfterEach
    void reset() {
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of());
        NpcOfferProviders.clear();
        QuestDialogueHosts.clear();
    }

    @Test
    void theAnswerSetComesFromTheIdentityAuthorityNotFromTheCaller() {
        loadAliasedGuide();
        NpcEncounter here = NpcEncounters.at(new BareContext("guide_wilds"), new ScriptedQuests());

        assertEquals("guide_wilds", here.npcId());
        assertEquals(List.of("guide_wilds", "adventurers_guide"), List.copyOf(here.answersTo()),
                "a surface must not have to know about aliases to be an NPC surface");
    }

    @Test
    void everyQuestQuestionWalksTheWholeAnswerSet() {
        loadAliasedGuide();
        ScriptedQuests quests = new ScriptedQuests();
        quests.deliverableAt = "adventurers_guide";

        NpcEncounter here = NpcEncounters.at(new BareContext("guide_wilds"), quests);

        assertTrue(here.deliverableHere("a_quest"),
                "a hand-in written against the shared name must work at this placement");
        assertEquals(List.of("guide_wilds", "adventurers_guide"), quests.deliverAsked,
                "primary first, then the alias, stopping at the one that takes it");
    }

    @Test
    void theDestinationReadAndTheCompletionReadStaySeparate() {
        loadAliasedGuide();
        ScriptedQuests quests = new ScriptedQuests();
        quests.deliverableAt = null;

        NpcEncounter here = NpcEncounters.at(new BareContext("guide_wilds"), quests);

        assertTrue(here.readyHere("a_quest"),
                "the step is going here, so a marker points at it even with nothing carried");
        assertFalse(here.deliverableHere("a_quest"),
                "but the button that would complete it must not be offered");
        assertFalse(quests.resolvesAsked.isEmpty());
    }

    @Test
    void handingInWalksTheAnswerSetUntilOneTakesIt() {
        loadAliasedGuide();
        ScriptedQuests quests = new ScriptedQuests();
        quests.deliverableAt = "adventurers_guide";

        assertTrue(NpcEncounters.at(new BareContext("guide_wilds"), quests).deliver("a_quest"));
        assertEquals(List.of("guide_wilds", "adventurers_guide"), quests.turnInAsked);
    }

    @Test
    void offersAreAskedOfEveryRegisteredProviderAtEveryAnsweredId() {
        loadAliasedGuide();
        List<Integer> setSizes = new ArrayList<>();
        NpcOfferProviders.register("mymod", "A", (subject, answersTo) -> {
            setSizes.add(answersTo.size());
            return List.of(NpcOffer.available("a_quest", null));
        });

        NpcEncounter here = NpcEncounters.at(new BareContext("guide_wilds"), new ScriptedQuests());

        assertEquals(List.of("a_quest"), here.offerableHere().stream().map(NpcOffer::id).toList());
        assertTrue(here.anythingOfferedHere());
        assertEquals(List.of(2, 2), setSizes, "the provider is handed the whole answer set, not one id");
    }

    @Test
    void aCharacterNobodyNamesAnswersNothingRatherThanEverything() {
        NpcOfferProviders.register("mymod", "A", (subject, answersTo) -> {
            throw new AssertionError("must not be asked about a nameless character");
        });
        NpcEncounter nowhere = NpcEncounters.at(new BareContext(null), new ScriptedQuests());

        assertEquals("", nowhere.npcId());
        assertTrue(nowhere.answersTo().isEmpty());
        assertTrue(nowhere.offerableHere().isEmpty());
        assertFalse(nowhere.anythingOfferedHere());
        assertFalse(nowhere.readyHere("a_quest"));
        assertFalse(nowhere.anythingDeliverableHere());
        assertFalse(nowhere.deliver("a_quest"));
        assertFalse(nowhere.creditTalk(null));
    }

    @Test
    void anEncounterRoutesTheHandOffOnItsPrimaryId() {
        loadAliasedGuide();
        ScriptedQuests quests = new ScriptedQuests();
        quests.completionDialogue = "guide_thanks";
        QuestDialogueHosts.register("mymod", "A", new ScriptedHost("guide_thanks"));

        QuestHandOff handOff = NpcEncounters.at(new BareContext("guide_wilds"), quests)
                .completionHandOff("a_quest");

        assertTrue(handOff.plays());
        assertEquals("guide_thanks", handOff.dialogueId());
        assertEquals("guide_wilds", handOff.npcId(),
                "the conversation is with the character the player is looking at, not with an alias of them");
    }

    @Test
    void aConversationDoesNotHandOffToItself() {
        loadAliasedGuide();
        ScriptedQuests quests = new ScriptedQuests();
        quests.completionDialogue = "guide_thanks";
        ScriptedHost host = new ScriptedHost("guide_thanks");
        QuestDialogueHosts.register("mymod", "A", host);

        assertFalse(NpcEncounters.at(new BareContext("guide_wilds"), quests).playCompletion("a_quest"),
                "a TurnIn beat is already on the screen the hand-off would open; it routes onward with Goto");
        assertTrue(host.openedAt.isEmpty());
    }

    @Test
    void anEncounterWithNoConversationInstalledSkipsTheBeat() {
        loadAliasedGuide();
        ScriptedQuests quests = new ScriptedQuests();
        quests.completionDialogue = "guide_thanks";

        assertEquals(QuestHandOff.Outcome.NO_HOST, NpcEncounters.at(new BareContext("guide_wilds"), quests)
                .completionHandOff("a_quest").outcome());
    }
}
