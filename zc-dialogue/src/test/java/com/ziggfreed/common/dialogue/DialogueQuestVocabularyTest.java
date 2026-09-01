package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.type.DialogueActionExecutor;
import com.ziggfreed.common.quest.QuestStatus;

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

    @Nonnull
    private static DialogueEngine engine(@Nonnull DialogueQuests quests) {
        return DialogueEngine.builder().warn(m -> { }).quests(quests).build();
    }

    @Nonnull
    private static NpcDialogue gated(@Nonnull DialogueEngine engine, @Nonnull String condition) {
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
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
        TestQuests quests = new TestQuests();
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
        TestQuests quests = new TestQuests();
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
        TestQuests quests = new TestQuests();
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
        TestQuests quests = new TestQuests();
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
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Do\":[{\"Accept\":\"Intro\"},{\"Goto\":\"g\"}]}]}}}");
        assertNotNull(d);
        DialogueOption option = d.getNode("g").getOptions().get(0);

        DialogueActionExecutor.Outcome outcome = engine.executor()
                .execute(option.getActions(), new TestDialogueContext(d).talkingTo("mmo_hub"));

        assertEquals(List.of("intro"), quests.accepted, "the id is normalized once, at the seam");
        assertEquals(List.of("intro@mmo_hub"), quests.acceptedAt,
                "the character the conversation is with travels as the SITE, so a quest that says"
                        + " 'report back to whoever gave me this' can resolve where that was");
        assertEquals("g", outcome.gotoNode(), "the jump beside it still runs");
    }

    @Test
    void turnInTriesEveryAnsweredIdAndReportsTheCompletion() {
        TestQuests quests = new TestQuests();
        quests.aliases.put("guide_temple", List.of("guide_temple", "mmo_hub"));
        quests.deliverableAt.add("errand@mmo_hub");
        DialogueEngine engine = engine(quests);
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
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
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Accept\":\"intro\",\"TurnIn\":\"intro\"}]}}}");
        assertNotNull(d);

        DialogueActionExecutor.Outcome outcome = engine.executor()
                .execute(d.getNode("g").getOptions().get(0).getActions(),
                        new TestDialogueContext(d).talkingTo("mmo_hub"));

        assertEquals(null, outcome.completedId());
    }
}
