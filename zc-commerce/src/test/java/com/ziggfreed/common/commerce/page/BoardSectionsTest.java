package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.QuestStatus;

/**
 * Where each contract sits on a board and in what order the board reads: the rules a player notices
 * immediately and a refactor breaks silently.
 */
class BoardSectionsTest {

    @Test
    @DisplayName("a finished contract is READY where it can be collected and parked where it cannot")
    void aFinishedContractDependsOnWhereYouAreStanding() {
        assertEquals(BoardSections.Section.READY, BoardSections.classify(
                QuestStatus.COMPLETED_UNCLAIMED, false, false, true, false));
        assertEquals(BoardSections.Section.ACTIVE, BoardSections.classify(
                QuestStatus.COMPLETED_UNCLAIMED, false, false, false, false),
                "its reward belongs at the board it was taken from, so it is still something carried");
    }

    @Test
    @DisplayName("a carried contract reads as a hand-in only where the delivery resolves")
    void aCarriedContractDependsOnTheDeliverySite() {
        assertEquals(BoardSections.Section.TURN_IN,
                BoardSections.classify(QuestStatus.ACTIVE, false, true, false, false));
        assertEquals(BoardSections.Section.ACTIVE,
                BoardSections.classify(QuestStatus.ACTIVE, false, false, false, false));
    }

    @Test
    @DisplayName("an unstarted contract is available, locked, or already spent this rotation")
    void anUnstartedContractHasThreeAnswers() {
        assertEquals(BoardSections.Section.AVAILABLE,
                BoardSections.classify(QuestStatus.NOT_STARTED, true, false, false, false));
        assertEquals(BoardSections.Section.LOCKED,
                BoardSections.classify(QuestStatus.NOT_STARTED, false, false, false, false));
        assertEquals(BoardSections.Section.SPENT,
                BoardSections.classify(QuestStatus.NOT_STARTED, true, false, false, true),
                "the period lock outranks the accept gate: it is spent, not merely refused");
    }

    @Test
    @DisplayName("a contract waiting out a clock is DRAWN rather than vanishing")
    void aSpentContractIsStillShown() {
        assertEquals(BoardSections.Section.SPENT,
                BoardSections.classify(QuestStatus.ON_COOLDOWN, false, false, false, false),
                "a daily that disappears between runs reads as content having been taken away");
        assertEquals(BoardSections.Section.DONE,
                BoardSections.classify(QuestStatus.COMPLETED, false, false, false, false));
    }

    @Test
    @DisplayName("the board reads by what you can do about it, most actionable first")
    void runsReadInActionableOrder() {
        List<BoardSections.Entry> entries = List.of(
                BoardSections.Entry.of("done", BoardSections.Section.DONE),
                BoardSections.Entry.of("locked", BoardSections.Section.LOCKED),
                BoardSections.Entry.of("ready", BoardSections.Section.READY),
                BoardSections.Entry.of("active", BoardSections.Section.ACTIVE),
                BoardSections.Entry.of("available", BoardSections.Section.AVAILABLE),
                BoardSections.Entry.of("turn_in", BoardSections.Section.TURN_IN),
                BoardSections.Entry.of("spent", BoardSections.Section.SPENT));

        assertEquals(List.of("ready", "turn_in", "active", "available", "locked", "spent", "done"),
                BoardSections.sortedIds(entries));
    }

    @Test
    @DisplayName("inside a run, the content's own order wins and an id settles the ties")
    void rowsSortByOrderThenId() {
        List<BoardSections.Entry> entries = List.of(
                new BoardSections.Entry("late", BoardSections.Section.AVAILABLE, 30),
                new BoardSections.Entry("b_same", BoardSections.Section.AVAILABLE, 10),
                new BoardSections.Entry("a_same", BoardSections.Section.AVAILABLE, 10));

        assertEquals(List.of("a_same", "b_same", "late"), BoardSections.sortedIds(entries));
    }

    @Test
    @DisplayName("a deep link beats a stale selection, and a surviving selection beats the first row")
    void selectionHasThreeRungs() {
        List<String> ids = List.of("first", "second", "third");

        assertEquals("third", BoardSections.select(ids, "third", "second"),
                "opening a board AT a contract must land on that contract");
        assertEquals("second", BoardSections.select(ids, null, "second"),
                "a refresh after an accept must not jump back to the top");
        assertEquals("second", BoardSections.select(ids, "gone", "second"),
                "a deep link to something not on the board falls through rather than emptying the panel");
        assertEquals("first", BoardSections.select(ids, null, null));
        assertNull(BoardSections.select(List.of(), "anything", "anything"));
    }
}
