package com.ziggfreed.common.objectives.book;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A quest with a giver is taken AT that giver: the book neither offers nor performs its accept.
 * The refusal is the BOOK's alone - the engine's own gate stays open, because the NPC quest page
 * is where such a quest is legitimately accepted and it calls the same engine.
 */
class GiverBoundQuestTest {

    @Test
    void aQuestWithAGiverIsGiverBoundAndAPlainOneIsNot() {
        assertTrue(BookQuestsTab.giverBound(
                Quest.builder("q_giver").npcViewId("Mmo_Mastery_Trainer").build()));
        assertFalse(BookQuestsTab.giverBound(Quest.builder("q_plain").build()));
    }

    /**
     * The engine must keep accepting giver-bound quests: the refusal lives in the book, never in
     * {@code QuestEngine.canAccept}, or the NPC quest page could no longer hand them out.
     */
    @Test
    void theEngineItselfStillAcceptsAGiverBoundQuest() {
        Quest quest = Quest.builder("q_giver").npcViewId("Mmo_Mastery_Trainer").build();
        QuestEngine engine = QuestEngine.builder().nativeEvents(false)
                .warn(message -> { }).build();
        engine.setQuests(List.of(quest));
        Subject player = Subject.of(UUID.randomUUID(), "tester");

        assertTrue(engine.canAccept(player, quest).allowed(),
                "the engine's gate stays open for the NPC quest page");
        assertTrue(engine.accept(player, quest),
                "accepting at the giver keeps working");
    }
}
