package com.ziggfreed.common.objectives.book.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.book.BookState;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.journal.QuestPresentation;
import com.ziggfreed.common.objectives.journal.QuestReader;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerSection;

/**
 * How the journal tab plans a screen from the book's state and the reader's model: which sections show their rows
 * (the defaults, a chosen status, the player's own word on a section), which row is selected (the selection rides
 * every reopen while the quest is still listed, else the first row of the first open section), the moved-row rule (a
 * verb that moves the selected quest into a section closed by default opens that section, unless the player closed
 * it by hand), and Show more raising one section's cap.
 */
class QuestJournalPlanTest {

    private QuestEngine engine;
    private Subject player;

    @BeforeEach
    void engine() {
        engine = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> true).maxActive(10).build();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    private static Quest quest(String id) {
        return Quest.builder(id).category("main")
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build();
    }

    @Nonnull
    private LedgerModel model(@Nonnull BookState state) {
        QuestReader reader = QuestReader.of(engine, player, QuestPresentation.of(ObjectiveBookDeps.DEFAULTS),
                player.id(), 0L);
        return reader.journal(reader.listed(), state.status(), state.category(), state.tag(), state.search());
    }

    private static boolean open(@Nonnull LedgerModel model, @Nonnull Set<String> open, @Nonnull String sectionId) {
        for (LedgerSection section : model.sections()) {
            if (section.id().equals(sectionId)) {
                return LedgerPainter.isOpen(section, open);
            }
        }
        throw new AssertionError("no section " + sectionId);
    }

    /** Ready, In progress and Available, plus a finished quest, a carried one and one ready to collect. */
    private void lifecycle() {
        Quest ready = quest("q_ready");
        Quest active = quest("q_active");
        Quest available = quest("q_available");
        Quest done = quest("q_done");
        engine.setQuests(List.of(ready, active, available, done));
        assertTrue(engine.accept(player, ready));
        engine.markUnclaimed(player, ready);
        assertTrue(engine.accept(player, active));
        assertTrue(engine.accept(player, done));
        engine.markCompleted(player, done);
    }

    @Test
    void theDefaultsOpenReadyInProgressAndAvailable() {
        lifecycle();
        BookState fresh = BookState.of(null);
        LedgerModel model = model(fresh);
        Set<String> open = QuestJournalPlan.openSections(model, fresh.openSections(), null);
        assertTrue(open(model, open, "ready"));
        assertTrue(open(model, open, "progress"));
        assertTrue(open(model, open, "available"));
        assertFalse(open(model, open, "completed"));
    }

    @Test
    void theFirstRowOfTheFirstOpenSectionIsSelectedOnOpen() {
        lifecycle();
        LedgerModel model = model(BookState.of(null));
        assertEquals("q_ready", QuestJournalPlan.selection(model, Set.of(), null));
        assertEquals("q_active", QuestJournalPlan.selection(model, Set.of(BookState.CLOSED + "ready"), null),
                "a section the player closed is skipped");
    }

    @Test
    void aChosenStatusShowsOnlyItsSectionsOpen() {
        lifecycle();
        BookState done = BookState.of(null).withFilters(null, "done", null, null, null);
        LedgerModel model = model(done);
        assertEquals(List.of("completed"), model.sections().stream().map(LedgerSection::id).toList());
        assertTrue(open(model, QuestJournalPlan.openSections(model, done.openSections(), null), "completed"));
        assertEquals("q_done", QuestJournalPlan.selection(model, done.openSections(), null));
    }

    @Test
    void theSelectionRidesEveryReopenWhileItIsListed() {
        lifecycle();
        BookState state = BookState.of(null).withSelected("q_available");
        BookState reopened = state.withFilters("main", null, null, null, null);
        assertEquals("q_available", reopened.selectedId(), "a reopen keeps the selection");
        assertEquals("q_available", QuestJournalPlan.selection(model(reopened), reopened.openSections(),
                reopened.selectedId()));

        BookState gone = state.withSelected("q_no_such_quest");
        assertEquals("q_ready", QuestJournalPlan.selection(model(gone), gone.openSections(), gone.selectedId()),
                "a selection no longer listed falls back to the first row");
    }

    @Test
    void aVerbThatMovesTheSelectedQuestOpensItsNewSection() {
        lifecycle();
        BookState state = BookState.of(null).withSelected("q_ready");
        engine.markCompleted(player, engine.quest("q_ready"));
        LedgerModel model = model(state);

        assertEquals("q_ready", QuestJournalPlan.selection(model, state.openSections(), state.selectedId()),
                "Collect lands back on the same quest");
        Set<String> open = QuestJournalPlan.openSections(model, state.openSections(), state.selectedId());
        assertTrue(open(model, open, "completed"), "its new section opens so the player sees where it went");

        BookState closedByHand = state.withSection("completed", false);
        Set<String> respected = QuestJournalPlan.openSections(model, closedByHand.openSections(),
                closedByHand.selectedId());
        assertFalse(open(model, respected, "completed"), "the player's own word on a section wins");
    }

    @Test
    void showMoreRaisesOneSectionsCap() {
        lifecycle();
        LedgerModel model = model(BookState.of(null));
        LedgerModel raised = QuestJournalPlan.withCaps(model, Map.of("available", 80));
        for (LedgerSection section : raised.sections()) {
            assertEquals("available".equals(section.id()) ? 80 : LedgerSection.DEFAULT_CAP, section.cap(),
                    section.id());
        }
        assertEquals(model.firstSelectable(), raised.firstSelectable());
    }

    @Test
    void anEmptyLogSelectsNothing() {
        engine.setQuests(List.of());
        LedgerModel model = model(BookState.of(null));
        assertTrue(model.isEmpty());
        assertNull(QuestJournalPlan.selection(model, Set.of(), "q_anything"));
    }
}
