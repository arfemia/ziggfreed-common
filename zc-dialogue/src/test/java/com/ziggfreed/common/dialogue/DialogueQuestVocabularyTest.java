package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The quest-aware conversation vocabulary, driven entirely through the two seams it is allowed to
 * see: a quest READER and an answer set. No quest engine is involved, which is the point - a
 * condition that could reach one could start a quest while merely deciding whether to show a line.
 */
class DialogueQuestVocabularyTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    // ==================== the fake quest runtime ====================

    /** A quest runtime made of two maps, plus a record of what was actually done to it. */
    private static final class FakeQuests implements DialogueQuests, QuestStateReader {

        final Map<String, QuestStatus> status = new LinkedHashMap<>();
        final Set<String> deliverableAt = new LinkedHashSet<>();
        final Map<String, Collection<String>> aliases = new LinkedHashMap<>();
        final List<String> accepted = new ArrayList<>();
        final List<String> handedIn = new ArrayList<>();
        boolean acceptSucceeds = true;

        @Nonnull
        @Override
        public QuestStateReader reader() {
            return this;
        }

        @Nonnull
        @Override
        public Subject subject(@Nonnull DialogueContext ctx) {
            return Subject.of(UUID.nameUUIDFromBytes("tester".getBytes()), "Tester");
        }

        @Nonnull
        @Override
        public Collection<String> answersTo(@Nullable String contextId) {
            if (contextId == null) {
                return List.of();
            }
            return aliases.getOrDefault(contextId, List.of(contextId));
        }

        @Override
        public boolean accept(@Nonnull Subject subject, @Nonnull String questId) {
            accepted.add(questId);
            return acceptSucceeds;
        }

        @Override
        public boolean turnIn(@Nonnull Subject subject, @Nonnull String questId, @Nullable String atId) {
            handedIn.add(questId + "@" + atId);
            return deliverableAt.contains(questId + "@" + atId);
        }

        @Nonnull
        @Override
        public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
            return status.getOrDefault(questId, QuestStatus.NOT_STARTED);
        }

        @Nullable
        @Override
        public ObjectiveProgressState objectiveProgress(@Nonnull Subject subject, @Nonnull String questId,
                                                        @Nonnull String objectiveId) {
            return null;
        }

        @Nonnull
        @Override
        public List<String> activeAndUnclaimedIds(@Nonnull Subject subject) {
            return List.copyOf(status.keySet());
        }

        @Override
        public boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                                          @Nullable String atId) {
            return deliverableAt.contains(questId + "@" + atId);
        }

        @Override
        public boolean hasDeliverableTurnInAt(@Nonnull Subject subject, @Nullable String atId) {
            return deliverableAt.stream().anyMatch(entry -> entry.endsWith("@" + atId));
        }
    }

    @Nonnull
    private static DialogueEngine engine(@Nonnull DialogueQuests quests) {
        return DialogueEngine.builder().warn(m -> { }).quests(quests).build();
    }

    @Nonnull
    private static NpcDialogue gated(@Nonnull DialogueEngine engine, @Nonnull String condition) {
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                        + "\"Conditions\":[" + condition + "]}]}}}");
        assertNotNull(d);
        return d;
    }

    private static boolean shows(@Nonnull DialogueEngine engine, @Nonnull NpcDialogue d,
                                 @Nullable String talkingTo) {
        DialogueOption option = d.getNode("g").getOptions().get(0);
        return engine.conditionsPass(option.getConditions(),
                new TestDialogueContext(d).talkingTo(talkingTo));
    }

    // ==================== conditions ====================

    @Test
    void questStateMatchesTheEffectiveStatus() {
        FakeQuests quests = new FakeQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue d = gated(engine, "{\"Type\":\"QuestState\",\"Quest\":\"intro\",\"State\":\"ACTIVE\"}");

        assertFalse(shows(engine, d, null), "an unknown quest reads NOT_STARTED");
        quests.status.put("intro", QuestStatus.ACTIVE);
        assertTrue(shows(engine, d, null));
        quests.status.put("intro", QuestStatus.COMPLETED);
        assertFalse(shows(engine, d, null));
    }

    @Test
    void statesIsAnOrShorthandAndAnUnknownNameNeverMatches() {
        FakeQuests quests = new FakeQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue any = gated(engine,
                "{\"Type\":\"QuestState\",\"Quest\":\"intro\",\"States\":[\"NOT_STARTED\",\"ACTIVE\"]}");
        assertTrue(shows(engine, any, null), "NOT_STARTED is one of the accepted states");
        quests.status.put("intro", QuestStatus.ACTIVE);
        assertTrue(shows(engine, any, null));
        quests.status.put("intro", QuestStatus.COMPLETED);
        assertFalse(shows(engine, any, null));

        NpcDialogue typo = gated(engine, "{\"Type\":\"QuestState\",\"Quest\":\"intro\",\"State\":\"DONE\"}");
        assertFalse(shows(engine, typo, null),
                "a state that does not exist hides the line rather than quietly meaning NOT_STARTED");
    }

    @Test
    void readyToTurnInAsksEveryIdTheCharacterAnswersTo() {
        FakeQuests quests = new FakeQuests();
        quests.aliases.put("guide_temple", List.of("guide_temple", "mmo_hub"));
        quests.deliverableAt.add("errand@mmo_hub");
        DialogueEngine engine = engine(quests);
        NpcDialogue d = gated(engine, "{\"Type\":\"ReadyToTurnIn\",\"Quest\":\"errand\"}");

        assertTrue(shows(engine, d, "guide_temple"),
                "the alias set is what lets one quest report back wherever its giver stands");
        assertFalse(shows(engine, d, "somebody_else"));
        assertFalse(shows(engine, d, null), "no character means no id to hand in at");
    }

    @Test
    void hasReadyToTurnInIsTheAnyQuestForm() {
        FakeQuests quests = new FakeQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue d = gated(engine, "{\"Type\":\"HasReadyToTurnIn\"}");

        assertFalse(shows(engine, d, "mmo_hub"));
        quests.deliverableAt.add("errand@mmo_hub");
        assertTrue(shows(engine, d, "mmo_hub"));
        assertFalse(shows(engine, d, "elsewhere"));
    }

    @Test
    void withNoQuestRuntimeWiredEveryQuestLineStaysHidden() {
        DialogueEngine engine = DialogueEngine.builder().warn(m -> { }).build();
        NpcDialogue active = gated(engine, "{\"Type\":\"QuestState\",\"Quest\":\"intro\",\"State\":\"ACTIVE\"}");
        NpcDialogue ready = gated(engine, "{\"Type\":\"ReadyToTurnIn\",\"Quest\":\"intro\"}");

        assertFalse(shows(engine, active, "mmo_hub"));
        assertFalse(shows(engine, ready, "mmo_hub"));
    }

    // ==================== actions ====================

    @Test
    void acceptShorthandStartsTheQuestThroughTheSeam() {
        FakeQuests quests = new FakeQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Do\":[{\"Accept\":\"Intro\"},{\"Goto\":\"g\"}]}]}}}");
        assertNotNull(d);
        DialogueOption option = d.getNode("g").getOptions().get(0);

        DialogueActionExecutor.Outcome outcome = engine.executor()
                .execute(option.getActions(), new TestDialogueContext(d).talkingTo("mmo_hub"));

        assertEquals(List.of("intro"), quests.accepted, "the id is normalized once, at the seam");
        assertEquals("g", outcome.gotoNode(), "the jump beside it still runs");
    }

    @Test
    void turnInTriesEveryAnsweredIdAndReportsTheCompletion() {
        FakeQuests quests = new FakeQuests();
        quests.aliases.put("guide_temple", List.of("guide_temple", "mmo_hub"));
        quests.deliverableAt.add("errand@mmo_hub");
        DialogueEngine engine = engine(quests);
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"TurnIn\":\"errand\"}]}}}");
        assertNotNull(d);

        DialogueActionExecutor.Outcome outcome = engine.executor()
                .execute(d.getNode("g").getOptions().get(0).getActions(),
                        new TestDialogueContext(d).talkingTo("guide_temple"));

        assertEquals(List.of("errand@guide_temple", "errand@mmo_hub"), quests.handedIn,
                "each answered id is tried in turn until one takes it");
        assertEquals("errand", outcome.completedId(), "a hand-in that went through floats its toast");
    }

    @Test
    void aQuestActionWithNoRuntimeWiredDoesNothingRatherThanThrowing() {
        DialogueEngine engine = DialogueEngine.builder().warn(m -> { }).build();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Accept\":\"intro\",\"TurnIn\":\"intro\"}]}}}");
        assertNotNull(d);

        DialogueActionExecutor.Outcome outcome = engine.executor()
                .execute(d.getNode("g").getOptions().get(0).getActions(),
                        new TestDialogueContext(d).talkingTo("mmo_hub"));

        assertEquals(null, outcome.completedId());
    }
}
