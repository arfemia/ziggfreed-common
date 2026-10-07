package com.ziggfreed.common.objectives.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.book.BookActions;
import com.ziggfreed.common.objectives.questlist.CharacterQuestListing;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;

/**
 * The action bar is read off the quest's live state, and a press dispatches on the state the quest is in when it
 * lands: the same button is Accept, then Hand in, then a gold Collect. The book never offers Accept for a giver-bound
 * or a board-managed quest; at a character the place decides instead.
 */
class QuestActionsTest {

    private static final String GUIDE = "guide";

    private QuestEngine engine;
    private Subject player;
    private QuestReaderTest.FakePresentation presentation;

    @BeforeEach
    void engine() {
        engine = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> true)
                .maxActive(10)
                .build();
        player = Subject.of(UUID.randomUUID(), "tester");
        presentation = new QuestReaderTest.FakePresentation();
    }

    private QuestReader reader() {
        return QuestReader.of(engine, player, presentation, player.id(), 0L);
    }

    private static Quest handIn(String id) {
        return Quest.builder(id)
                .objective(ObjectiveDef.builder("give", "TURN_IN").target("Copper_Ore").amount(3).build())
                .build();
    }

    private static Quest reportBack(String id) {
        return Quest.builder(id).npcViewId(GUIDE)
                .objective(ObjectiveDef.builder("tell", "TURN_IN").target("").amount(1).turnInLockId(GUIDE).build())
                .build();
    }

    @Nullable
    private static String verb(@Nonnull List<DetailAction> actions, @Nonnull ActionSlot slot) {
        for (DetailAction action : actions) {
            if (action.slot() == slot) {
                return action.actionId();
            }
        }
        return null;
    }

    @Test
    void theVerbsAreTheBooksOwnActions() {
        assertEquals(BookActions.ACCEPT, QuestActions.ACCEPT);
        assertEquals(BookActions.HAND_IN, QuestActions.HAND_IN);
        assertEquals(BookActions.COLLECT, QuestActions.COLLECT);
        assertEquals(BookActions.ABANDON, QuestActions.ABANDON);
        assertEquals(BookActions.TRACK, QuestActions.TRACK);
    }

    @Test
    void onePrimaryButtonFollowsTheQuestThroughItsLife() {
        Quest quest = handIn("q_hand");
        engine.setQuests(List.of(quest));

        assertEquals(QuestActions.ACCEPT, QuestActions.dispatch(ActionSlot.PRIMARY, quest, reader()));
        assertNull(QuestActions.dispatch(ActionSlot.DANGER, quest, reader()), "nothing to drop yet");

        assertTrue(engine.accept(player, quest));
        assertEquals(QuestActions.HAND_IN, QuestActions.dispatch(ActionSlot.PRIMARY, quest, reader()));
        assertEquals(QuestActions.ABANDON, QuestActions.dispatch(ActionSlot.DANGER, quest, reader()));
        assertEquals(QuestActions.TRACK, QuestActions.dispatch(ActionSlot.SECONDARY, quest, reader()),
                "a carried quest tracks from whichever control a page binds");

        engine.markUnclaimed(player, quest);
        assertEquals(QuestActions.COLLECT, QuestActions.dispatch(ActionSlot.PRIMARY, quest, reader()));
        assertNull(QuestActions.dispatch(ActionSlot.DANGER, quest, reader()));

        engine.markCompleted(player, quest);
        for (ActionSlot slot : ActionSlot.values()) {
            assertNull(QuestActions.dispatch(slot, quest, reader()), "nothing left to press: " + slot);
        }
    }

    @Test
    void aCarriedQuestWithNoHandInHasNoPrimary() {
        Quest quest = Quest.builder("q_mine")
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build();
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest));
        assertNull(QuestActions.dispatch(ActionSlot.PRIMARY, quest, reader()));
    }

    @Test
    void noAcceptForAGiverBoundOrABoardManagedQuestInTheBook() {
        Quest giver = Quest.builder("q_giver").npcViewId("Mmo_Mastery_Trainer").build();
        Quest board = Quest.builder("q_board").build();
        engine.setQuests(List.of(giver, board));
        presentation.managed.add(board.id());

        assertNull(QuestActions.dispatch(ActionSlot.PRIMARY, giver, reader()), "taken at its giver");
        assertNull(QuestActions.dispatch(ActionSlot.PRIMARY, board, reader()), "taken at its board");
        assertNull(verb(QuestActions.of(giver, reader()), ActionSlot.PRIMARY));
        assertNull(verb(QuestActions.of(board, reader()), ActionSlot.PRIMARY));
    }

    @Test
    void atItsGiverAGiverBoundQuestIsAccepted() {
        Quest giver = Quest.builder("q_giver").npcViewId(GUIDE).build();
        engine.setQuests(List.of(giver));
        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, Set.of(GUIDE));
        assertEquals(QuestActions.ACCEPT, QuestActions.dispatch(ActionSlot.PRIMARY, giver, reader(), atGuide));
    }

    @Test
    void atACharacterAReportBackIsCompletedWhereItSettles() {
        Quest quest = reportBack("q_report");
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest, GUIDE));

        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, Set.of(GUIDE));
        List<DetailAction> there = QuestActions.of(quest, reader(), atGuide);
        assertEquals(QuestActions.HAND_IN, verb(there, ActionSlot.PRIMARY));
        DetailAction primary = there.stream().filter(a -> a.slot() == ActionSlot.PRIMARY).findFirst().orElseThrow();
        assertEquals("ziggfreedcommon.progression.npcquests.action.complete", primary.label().getMessageId(),
                "a hand-in that delivers nothing reads as finishing the step");

        CharacterQuestListing elsewhere = new CharacterQuestListing(engine, player, Set.of("stranger"));
        assertNull(verb(QuestActions.of(quest, reader(), elsewhere), ActionSlot.PRIMARY));
        assertNull(verb(QuestActions.of(quest, reader()), ActionSlot.PRIMARY),
                "a place-locked hand-in is never handed in from the book");
    }

    @Test
    void atACharacterAQuestParkedForAnotherSiteHasNoCollect() {
        Quest quest = Quest.builder("q_site").turnInAt(QuestTurnInSite.character(GUIDE))
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build();
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest, GUIDE));
        engine.markUnclaimed(player, quest);

        CharacterQuestListing stranger = new CharacterQuestListing(engine, player, Set.of("stranger"));
        assertNull(QuestActions.dispatch(ActionSlot.PRIMARY, quest, reader(), stranger), "parked: nothing to press");
        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, Set.of(GUIDE));
        assertEquals(QuestActions.COLLECT, QuestActions.dispatch(ActionSlot.PRIMARY, quest, reader(), atGuide));
    }

    @Test
    void eachButtonWearsItsLook() {
        Quest quest = handIn("q_hand");
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest));
        List<DetailAction> carried = QuestActions.of(quest, reader());
        DetailAction abandon = carried.stream().filter(a -> a.slot() == ActionSlot.DANGER).findFirst().orElseThrow();
        assertEquals(ActionLook.DANGER, abandon.look(), "Abandon is red with its own sound");
        assertEquals("ziggfreedcommon.journal.action.abandon", abandon.label().getMessageId());
        assertNull(verb(carried, ActionSlot.SECONDARY), "the book tracks from the page's toggle");
        assertEquals(QuestActions.TRACK, verb(QuestActions.of(quest, reader(), null, true), ActionSlot.SECONDARY),
                "a page that wants Track in the bar asks for it");

        engine.markUnclaimed(player, quest);
        DetailAction collect = QuestActions.of(quest, reader()).get(0);
        assertEquals(ActionSlot.PRIMARY, collect.slot());
        assertEquals(ActionLook.COLLECT, collect.look(), "Collect is gold");
        assertEquals("ziggfreedcommon.journal.action.collect", collect.label().getMessageId());
    }
}
