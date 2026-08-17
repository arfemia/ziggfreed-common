package com.ziggfreed.common.dialogue.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.quest.QuestStateReader;

/**
 * The one policy deciding what follows a quest settling.
 *
 * <p>What is worth pinning is every way the beat is SKIPPED, because each of them is a screen that
 * would otherwise go blank or a line that would be spoken by nobody: no conversation authored, no
 * character in front of the player, and nothing installed that could open the conversation named.
 * The book-and-log case is the reason the policy is here at all rather than in each UI.
 */
class QuestCompletionRoutingTest {

    /**
     * The engine handles a hand-off carries but never reads. The decision is made from the quest, the
     * character and the host table alone, and a host in these tests paints nothing, so there is
     * nothing here for a live store to contribute.
     */
    private static final Store<EntityStore> NO_STORE = null;
    private static final Ref<EntityStore> NO_REF = null;
    private static final Player NO_PLAYER = null;

    /** A consumer catalogue: which conversation each quest names, and nothing else. */
    private record CatalogueQuests(@Nonnull Map<String, String> byQuest) implements DialogueQuests {

        @Override
        @Nonnull
        public QuestStateReader reader() {
            return DialogueQuests.NONE.reader();
        }

        @Override
        @Nullable
        public String completionDialogueOf(@Nonnull String questId) {
            return byQuest.get(questId);
        }
    }

    /** A host that knows a fixed set of conversations and records every one it was asked to open. */
    private static final class ScriptedHost implements QuestDialogueHost {

        private final List<String> knownIds;
        private final boolean takesTheScreen;
        final List<String> opened = new ArrayList<>();

        ScriptedHost(@Nonnull List<String> knownIds, boolean takesTheScreen) {
            this.knownIds = knownIds;
            this.takesTheScreen = takesTheScreen;
        }

        @Override
        public boolean knows(@Nonnull String dialogueId) {
            return knownIds.contains(dialogueId);
        }

        @Override
        public boolean open(@Nonnull QuestHandOff handOff, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
            opened.add(handOff.dialogueId());
            return takesTheScreen;
        }
    }

    @BeforeEach
    @AfterEach
    void reset() {
        QuestDialogueHosts.clear();
    }

    @Test
    void aQuestThatNamesNoConversationHandsOffNothing() {
        QuestHandOff handOff = QuestCompletionRouting.decide("a_quest", "guide", (String) null);

        assertEquals(QuestHandOff.Outcome.NONE_AUTHORED, handOff.outcome());
        assertFalse(handOff.plays(), "most quests just pay out");
        assertNull(handOff.dialogueId());
    }

    @Test
    void nobodyInFrontOfThePlayerSkipsTheBeat() {
        QuestDialogueHosts.register("mymod", "A", new ScriptedHost(List.of("guide_thanks"), true));

        assertEquals(QuestHandOff.Outcome.NO_NPC_CONTEXT,
                QuestCompletionRouting.decide("a_quest", null, "guide_thanks").outcome(),
                "a quest log or a book has nobody to speak the lines");
        assertEquals(QuestHandOff.Outcome.NO_NPC_CONTEXT,
                QuestCompletionRouting.decide("a_quest", "  ", "guide_thanks").outcome(),
                "and a surface that passed an empty context has not found one either");
    }

    @Test
    void aConversationNothingCanOpenSkipsRatherThanBlankingTheScreen() {
        QuestDialogueHosts.register("mymod", "A", new ScriptedHost(List.of("something_else"), true));

        QuestHandOff handOff = QuestCompletionRouting.decide("a_quest", "guide", "guide_thanks");

        assertEquals(QuestHandOff.Outcome.NO_HOST, handOff.outcome());
        assertFalse(handOff.plays(),
                "the caller has already returned from its own refresh, so a dead open is a dead screen");
    }

    @Test
    void anAuthoredConversationAtACharacterPlays() {
        QuestDialogueHosts.register("mymod", "A", new ScriptedHost(List.of("guide_thanks"), true));

        QuestHandOff handOff = QuestCompletionRouting.decide("a_quest", "guide", "guide_thanks");

        assertTrue(handOff.plays());
        assertEquals("guide_thanks", handOff.dialogueId());
        assertEquals("guide", handOff.npcId(), "the character is carried so the host needs no second lookup");
    }

    @Test
    void theFirstHostThatKnowsItIsTheOneThatOpensIt() {
        ScriptedHost strangerToIt = new ScriptedHost(List.of("something_else"), true);
        ScriptedHost knowsIt = new ScriptedHost(List.of("guide_thanks"), true);
        ScriptedHost alsoKnowsIt = new ScriptedHost(List.of("guide_thanks"), true);
        QuestDialogueHosts.register("aaa_mod", "A", strangerToIt);
        QuestDialogueHosts.register("mmm_mod", "B", knowsIt);
        QuestDialogueHosts.register("zzz_mod", "C", alsoKnowsIt);

        assertTrue(QuestCompletionRouting.handOff("a_quest", "guide", new CatalogueQuests(
                Map.of("a_quest", "guide_thanks")), NO_STORE, NO_REF, NO_PLAYER));

        assertTrue(strangerToIt.opened.isEmpty(), "a host is never asked to open what it does not know");
        assertEquals(List.of("guide_thanks"), knowsIt.opened);
        assertTrue(alsoKnowsIt.opened.isEmpty(),
                "ids are walked sorted, so which of two knowing hosts wins is the same after a restart");
    }

    @Test
    void aHostThatThrowsCostsOnlyItsOwnAnswer() {
        ScriptedHost working = new ScriptedHost(List.of("guide_thanks"), true);
        QuestDialogueHosts.register("aaa_mod", "A", new QuestDialogueHost() {

            @Override
            public boolean knows(@Nonnull String dialogueId) {
                throw new IllegalStateException("this mod's catalogue is broken");
            }

            @Override
            public boolean open(@Nonnull QuestHandOff handOff, @Nonnull Store<EntityStore> store,
                    @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
                throw new IllegalStateException("this mod's page is broken too");
            }
        });
        QuestDialogueHosts.register("zzz_mod", "B", working);

        assertTrue(QuestCompletionRouting.handOff("a_quest", "guide", new CatalogueQuests(
                Map.of("a_quest", "guide_thanks")), NO_STORE, NO_REF, NO_PLAYER));
        assertEquals(List.of("guide_thanks"), working.opened);
        assertTrue(QuestDialogueHosts.info().get("aaa_mod").failures() > 0,
                "the failure is counted against the mod that owns it, and against nobody else");
        assertEquals(0L, QuestDialogueHosts.info().get("zzz_mod").failures());
    }

    @Test
    void aHostThatDeclinesToOpenLeavesTheCallerItsOwnRefresh() {
        ScriptedHost declines = new ScriptedHost(List.of("guide_thanks"), false);
        QuestDialogueHosts.register("mymod", "A", declines);

        assertFalse(QuestCompletionRouting.handOff("a_quest", "guide", new CatalogueQuests(
                Map.of("a_quest", "guide_thanks")), NO_STORE, NO_REF, NO_PLAYER),
                "false means nothing was painted, so the caller still owes the player a response");
        assertEquals(List.of("guide_thanks"), declines.opened, "it was asked, it simply did not take over");
    }

    @Test
    void theSeamOverloadReadsTheAuthoredIdThroughDialogueQuests() {
        QuestDialogueHosts.register("mymod", "A", new ScriptedHost(List.of("guide_thanks"), true));
        DialogueQuests quests = new CatalogueQuests(Map.of("a_quest", "guide_thanks"));

        assertEquals("guide_thanks",
                QuestCompletionRouting.decide("a_quest", "guide", quests).dialogueId());
        assertEquals(QuestHandOff.Outcome.NONE_AUTHORED,
                QuestCompletionRouting.decide("another_quest", "guide", quests).outcome(),
                "a quest the catalogue says nothing about names no conversation");
    }

    @Test
    void aCatalogueThatCannotAnswerCostsTheBeatAndNothingElse() {
        QuestDialogueHosts.register("mymod", "A", new ScriptedHost(List.of("guide_thanks"), true));
        DialogueQuests broken = new DialogueQuests() {

            @Override
            @Nonnull
            public QuestStateReader reader() {
                return DialogueQuests.NONE.reader();
            }

            @Override
            @Nullable
            public String completionDialogueOf(@Nonnull String questId) {
                throw new IllegalStateException("the catalogue is mid-reload");
            }
        };

        assertEquals(QuestHandOff.Outcome.NONE_AUTHORED,
                QuestCompletionRouting.decide("a_quest", "guide", broken).outcome(),
                "a hand-in must never fail because the beat after it could not be looked up");
    }
}
