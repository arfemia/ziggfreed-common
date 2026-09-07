package com.ziggfreed.common.objectives.questlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Entry;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.quest.QuestStatus;

/**
 * The NPC quest list's ordering rules: which bucket a quest lands in, which row reads first, and
 * which quest the detail panel opens on. Pure decisions, so they are assertable with no engine and no
 * server - which matters because these are the rules a player notices immediately and a refactor
 * breaks silently.
 */
class NpcQuestSectionsTest {

    // ==================== classify ====================

    @Test
    void aFinishedQuestCollectableHereIsReadyWhateverElseIsTrue() {
        assertEquals(Section.READY,
                NpcQuestSections.classify(QuestStatus.COMPLETED_UNCLAIMED, false, false, true));
        assertEquals(Section.READY,
                NpcQuestSections.classify(QuestStatus.COMPLETED_UNCLAIMED, true, true, true));
    }

    @Test
    void aFinishedQuestBelongingToSomebodyElseReadsAsParkedRatherThanReady() {
        // The whole point of a collection site: showing Ready here would offer a button the engine
        // itself refuses, which reads as a broken menu rather than as "go back to whoever sent you".
        assertEquals(Section.PARKED,
                NpcQuestSections.classify(QuestStatus.COMPLETED_UNCLAIMED, true, true, false));
    }

    @Test
    void theCollectionSiteOnlyDecidesAnythingForAFinishedQuest() {
        // Every other status ignores it, so a site-bound quest still reads normally while carried.
        assertEquals(Section.ACTIVE, NpcQuestSections.classify(QuestStatus.ACTIVE, false, false, false));
        assertEquals(Section.AVAILABLE,
                NpcQuestSections.classify(QuestStatus.NOT_STARTED, true, false, false));
        assertEquals(Section.DONE, NpcQuestSections.classify(QuestStatus.COMPLETED, false, false, false));
    }

    @Test
    void aCarriedQuestSplitsOnWhetherThisCharacterIsWhereItResolves() {
        assertEquals(Section.TURN_IN, NpcQuestSections.classify(QuestStatus.ACTIVE, false, true, true));
        assertEquals(Section.ACTIVE, NpcQuestSections.classify(QuestStatus.ACTIVE, false, false, true));
    }

    @Test
    void anUnstartedQuestSplitsOnTheAcceptGate() {
        assertEquals(Section.AVAILABLE,
                NpcQuestSections.classify(QuestStatus.NOT_STARTED, true, false, true));
        assertEquals(Section.LOCKED,
                NpcQuestSections.classify(QuestStatus.NOT_STARTED, false, false, true));
    }

    @Test
    void aRepeatableWaitingOutItsClockReadsAsComingBackRatherThanLockedOrVanishing() {
        // Leaving it out entirely makes a daily disappear between runs, which reads to a player as
        // content having been taken away rather than as a wait; filing it under Locked says a gate
        // refuses it when nothing does. It gets its own heading, and Locked means only "refused".
        assertEquals(Section.COOLDOWN,
                NpcQuestSections.classify(QuestStatus.ON_COOLDOWN, true, false, true));
        assertEquals(Section.COOLDOWN,
                NpcQuestSections.classify(QuestStatus.ON_COOLDOWN, false, false, false),
                "the wait is the whole story: neither the gate nor the place changes it");
    }

    @Test
    void aCollectedQuestIsDone() {
        assertEquals(Section.DONE,
                NpcQuestSections.classify(QuestStatus.COMPLETED, false, false, true));
    }

    // ==================== sort ====================

    // ==================== membership ====================

    @Test
    void aQuestWhoseStepResolvesHereOrThatWasTakenHereBelongsWhateverItsStatus() {
        assertTrue(NpcQuestSections.belongsHere(QuestStatus.ACTIVE, true, false, false));
        assertTrue(NpcQuestSections.belongsHere(QuestStatus.ACTIVE, false, true, false));
        assertTrue(NpcQuestSections.belongsHere(QuestStatus.COMPLETED_UNCLAIMED, false, true, false));
    }

    @Test
    void aFinishedQuestCollectableHereBelongsEvenWhereItWasNeitherGivenNorHandedIn() {
        assertTrue(NpcQuestSections.belongsHere(QuestStatus.COMPLETED_UNCLAIMED, false, false, true),
                "credited by a beat at a character that neither gave it nor is its hand-in");
    }

    @Test
    void beingCollectableHereAdmitsNothingThatIsNotWaitingToBeCollected() {
        assertFalse(NpcQuestSections.belongsHere(QuestStatus.ACTIVE, false, false, true),
                "a quest naming no site is collectable everywhere, so this alone must not list it");
        assertFalse(NpcQuestSections.belongsHere(QuestStatus.COMPLETED_UNCLAIMED, false, false, false),
                "finished but belonging somewhere else");
    }

    @Test
    void sectionsReadInTheOrderTheyAreDeclared() {
        // Most actionable HERE first: a reward to take, a step to hand over, what is being carried,
        // what can be taken on, then the three nothing can be done about here (collected elsewhere,
        // coming back on its own, refused by a gate), then what is finished.
        List<String> ids = NpcQuestSections.sortedIds(List.of(
                Entry.of("done", Section.DONE, false),
                Entry.of("locked", Section.LOCKED, false),
                Entry.of("cooldown", Section.COOLDOWN, false),
                Entry.of("parked", Section.PARKED, false),
                Entry.of("available", Section.AVAILABLE, false),
                Entry.of("active", Section.ACTIVE, false),
                Entry.of("turnin", Section.TURN_IN, false),
                Entry.of("ready", Section.READY, false)));
        assertEquals(List.of("ready", "turnin", "active", "available", "parked", "cooldown", "locked",
                "done"), ids);
    }

    @Test
    void aHighlightedQuestIsTheFirstRowWhateverSectionItBelongsTo() {
        // There is no scroll-to on a page, so being the first row IS "take me to it".
        List<String> ids = NpcQuestSections.sortedIds(List.of(
                Entry.of("ready", Section.READY, false),
                Entry.of("active", Section.ACTIVE, false),
                Entry.of("routed", Section.DONE, true)));
        assertEquals("routed", ids.get(0));
    }

    @Test
    void oneSectionOrdersByTheConsumersOwnOrderThenById() {
        List<String> ids = NpcQuestSections.sortedIds(List.of(
                new Entry("b", Section.AVAILABLE, false, 5),
                new Entry("a", Section.AVAILABLE, false, 5),
                new Entry("z", Section.AVAILABLE, false, 1)));
        assertEquals(List.of("z", "a", "b"), ids);
    }

    // ==================== select ====================

    @Test
    void aRoutedHighlightBeatsAStaleSelection() {
        // Otherwise a hand-in routed from a conversation opens on whatever the player last clicked.
        assertEquals("routed",
                NpcQuestSections.select(List.of("first", "routed", "other"), "routed", "other"));
    }

    @Test
    void aSurvivingSelectionBeatsTheFirstRow() {
        // Otherwise every refresh after an accept jumps the player back to the top of the list.
        assertEquals("other",
                NpcQuestSections.select(List.of("first", "other"), null, "other"));
    }

    @Test
    void aSelectionThatLeftTheListFallsBackToTheFirstRow() {
        assertEquals("first",
                NpcQuestSections.select(List.of("first", "other"), null, "gone"));
    }

    @Test
    void aHighlightThatIsNotOnThisListIsIgnoredRatherThanSelected() {
        assertEquals("first",
                NpcQuestSections.select(List.of("first", "other"), "elsewhere", null));
    }

    @Test
    void anEmptyListSelectsNothing() {
        assertNull(NpcQuestSections.select(List.of(), "routed", "other"));
    }
}
