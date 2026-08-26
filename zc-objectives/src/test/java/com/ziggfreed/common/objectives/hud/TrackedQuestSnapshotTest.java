package com.ziggfreed.common.objectives.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * What one paint of the tracker SHOWS, read as plain values over an in-memory engine: hidden when
 * nothing is pinned, one block per pinned quest, only the current step's rows, the count blank on
 * a report-back hand-in, complete flipping the row's state, and the deps' own switches hiding the
 * whole panel. Structure and invariants only; the drawing of those values onto the document is
 * in-game smoke.
 */
class TrackedQuestSnapshotTest {

    private QuestEngine engine;
    private Subject player;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        engine = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Nonnull
    private static ObjectiveDef objective(@Nonnull String id, @Nonnull String kind,
            @Nonnull String target, long amount, int order) {
        return ObjectiveDef.builder(id, kind).target(target).matchMode(MatchMode.EXACT)
                .amount(amount).order(order).build();
    }

    @Nonnull
    private static Quest gather(@Nonnull String id) {
        return Quest.builder(id)
                .objective(objective("logs", "BREAK_BLOCK", "Oak_Log", 3, 0))
                // A claim reward is what makes a finished quest WAIT (and stay on the tracker).
                .reward(RewardSpec.of("NOTE", "text", "parked"))
                .build();
    }

    @Nonnull
    private TrackedQuestSnapshot snapshot() {
        return TrackedQuestSnapshot.of(engine, player, TrackedQuestHudDeps.DEFAULTS);
    }

    // ==================== visibility ====================

    @Test
    void nothingPinnedMeansAHiddenPanelWithNoBlocks() {
        Quest quest = gather("q_logs");
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);

        TrackedQuestSnapshot snapshot = snapshot();
        assertFalse(snapshot.panelVisible());
        assertTrue(snapshot.blocks().isEmpty());
        assertTrue(snapshot.questIds().isEmpty());
    }

    @Test
    void nobodyToReadIsHidden() {
        assertSame(TrackedQuestSnapshot.HIDDEN,
                TrackedQuestSnapshot.of(engine, null, TrackedQuestHudDeps.DEFAULTS));
    }

    @Test
    void onePinnedQuestShowsThePanelWithOneBlock() {
        Quest quest = gather("q_logs");
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());

        TrackedQuestSnapshot snapshot = snapshot();
        assertTrue(snapshot.panelVisible());
        assertEquals(1, snapshot.blocks().size());
        assertEquals("q_logs", snapshot.blocks().get(0).questId());
        assertNotNull(snapshot.blocks().get(0).title(), "a quest nothing names still gets the placeholder line");
        assertEquals(1, snapshot.blocks().get(0).rows().size());
        assertTrue(snapshot.questIds().contains("q_logs"));
    }

    @Test
    void aFinishedQuestLeavesTheTracker() {
        Quest quest = gather("q_logs");
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 3);

        assertFalse(snapshot().panelVisible(), "a completed quest is no longer carried, so it is not shown");
    }

    @Test
    void theOwnerSwitchAndThePlayersOwnAnswerBothHideThePanel() {
        Quest quest = gather("q_logs");
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());

        TrackedQuestHudDeps off = TrackedQuestHudDeps.builder().enabled(() -> false).build();
        assertFalse(TrackedQuestSnapshot.of(engine, player, off).panelVisible());

        TrackedQuestHudDeps hidden = TrackedQuestHudDeps.builder().audience(subject -> false).build();
        assertFalse(TrackedQuestSnapshot.of(engine, player, hidden).panelVisible());

        TrackedQuestHudDeps asked = TrackedQuestHudDeps.builder()
                .audience(subject -> subject.id().equals(player.id())).build();
        assertTrue(TrackedQuestSnapshot.of(engine, player, asked).panelVisible(),
                "the audience is asked about THIS subject");
    }

    // ==================== rows ====================

    @Test
    void aRowCarriesItsCountAndFlipsToCompleteWhenDone() {
        Quest quest = gather("q_logs");
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());

        TrackedQuestSnapshot.Row before = snapshot().blocks().get(0).rows().get(0);
        assertEquals("0/3", before.count());
        assertFalse(before.complete());

        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);
        TrackedQuestSnapshot.Row moved = snapshot().blocks().get(0).rows().get(0);
        assertEquals("2/3", moved.count());
        assertFalse(moved.complete());
    }

    @Test
    void aFinishedRowInTheCurrentStepReadsCompleteWhileItsNeighbourDoesNot() {
        Quest quest = Quest.builder("q_pair")
                .objective(objective("logs", "BREAK_BLOCK", "Oak_Log", 1, 0))
                .objective(objective("stone", "BREAK_BLOCK", "Stone", 2, 0))
                .reward(RewardSpec.of("NOTE", "text", "parked"))
                .build();
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

        List<TrackedQuestSnapshot.Row> rows = snapshot().blocks().get(0).rows();
        assertEquals(2, rows.size(), "an unordered pair stays on screen together");
        assertTrue(rows.get(0).complete());
        assertEquals("1/1", rows.get(0).count(), "a finished row reads required over required");
        assertFalse(rows.get(1).complete());
        assertEquals("0/2", rows.get(1).count());
    }

    @Test
    void onlyTheCurrentStepsRowsAreShownAndTheListAdvances() {
        Quest quest = Quest.builder("q_steps")
                .objective(objective("logs", "BREAK_BLOCK", "Oak_Log", 1, 1))
                .objective(objective("stone", "BREAK_BLOCK", "Stone", 1, 2))
                .reward(RewardSpec.of("NOTE", "text", "parked"))
                .build();
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());

        List<TrackedQuestSnapshot.Row> first = snapshot().blocks().get(0).rows();
        assertEquals(1, first.size(), "the second step waits its turn");
        assertEquals("0/1", first.get(0).count());

        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        List<TrackedQuestSnapshot.Row> second = snapshot().blocks().get(0).rows();
        assertEquals(1, second.size(), "the list advanced to the next step");
        assertFalse(second.get(0).complete());
    }

    @Test
    void aReportBackHandInShowsNoCount() {
        Quest quest = Quest.builder("q_report")
                .objective(objective("logs", "BREAK_BLOCK", "Oak_Log", 1, 0))
                .objective(ObjectiveDef.builder("back", "TURN_IN").amount(1).build())
                .reward(RewardSpec.of("NOTE", "text", "parked"))
                .build();
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());

        List<TrackedQuestSnapshot.Row> rows = snapshot().blocks().get(0).rows();
        assertEquals(2, rows.size());
        assertEquals("0/1", rows.get(0).count());
        assertEquals("", rows.get(1).count(), "a go-back-and-speak step is done or not, no count");
    }

    @Test
    void anItemHandInKeepsItsCount() {
        Quest quest = Quest.builder("q_deliver")
                .objective(ObjectiveDef.builder("bring", "TURN_IN").target("Oak_Log")
                        .matchMode(MatchMode.EXACT).amount(4).build())
                .reward(RewardSpec.of("NOTE", "text", "parked"))
                .build();
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());

        assertEquals("0/4", snapshot().blocks().get(0).rows().get(0).count());
    }

    // ==================== slots ====================

    @Test
    void blocksAndRowsAreCappedAtTheDocumentsSlots() {
        // More quests than the document has blocks, one of them with more objectives than a block
        // has rows, on an engine whose own cap is wider than the document.
        Quest.Builder wide = Quest.builder("q_wide")
                .reward(RewardSpec.of("NOTE", "text", "parked"));
        for (int i = 0; i < TrackedQuestSnapshot.MAX_ROWS + 2; i++) {
            wide.objective(objective("o" + i, "BREAK_BLOCK", "Block_" + i, 1, 0));
        }
        List<Quest> quests = new ArrayList<>();
        quests.add(wide.build());
        for (int i = 0; i < TrackedQuestSnapshot.MAX_QUESTS + 2; i++) {
            quests.add(gather("q_" + i));
        }
        QuestEngine roomy = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .maxTracked(quests.size())
                .warn(message -> { })
                .build();
        roomy.setQuests(quests);
        for (Quest quest : quests) {
            roomy.accept(player, quest);
            roomy.track(player, quest.id());
        }
        assertEquals(quests.size(), roomy.trackedActive(player).size(), "the engine itself carries them all");

        TrackedQuestSnapshot snapshot = TrackedQuestSnapshot.of(roomy, player, TrackedQuestHudDeps.DEFAULTS);
        assertEquals(TrackedQuestSnapshot.MAX_QUESTS, snapshot.blocks().size(), "blocks capped at the document's");
        for (TrackedQuestSnapshot.Block block : snapshot.blocks()) {
            assertTrue(block.rows().size() <= TrackedQuestSnapshot.MAX_ROWS, "rows capped at the document's");
        }

        // The wide quest alone, so its row cap is what is being read rather than which block it landed in.
        QuestEngine one = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        one.setQuests(List.of(quests.get(0)));
        one.accept(player, quests.get(0));
        one.track(player, "q_wide");
        TrackedQuestSnapshot wideOnly = TrackedQuestSnapshot.of(one, player, TrackedQuestHudDeps.DEFAULTS);
        assertEquals(TrackedQuestSnapshot.MAX_ROWS, wideOnly.blocks().get(0).rows().size());
    }

    // ==================== naming ====================

    @Test
    void titlesAndLinesComeFromTheRegisteredTextSources() {
        Message title = Message.translation("test.title");
        Message line = Message.translation("test.line");
        ProgressionRuntime.registrar("test").textSource(new ProgressionTextSource() {
            @Override
            public Message title(@Nonnull String contentId) {
                return "q_logs".equals(contentId) ? title : null;
            }

            @Override
            public Message flavor(@Nonnull String contentId) {
                return null;
            }

            @Override
            public Message objective(@Nonnull String contentId, @Nonnull String objectiveId) {
                return "logs".equals(objectiveId) ? line : null;
            }
        });
        Quest quest = gather("q_logs");
        engine.setQuests(List.of(quest));
        engine.accept(player, quest);
        engine.track(player, quest.id());

        TrackedQuestSnapshot.Block block = snapshot().blocks().get(0);
        assertSame(title, block.title());
        assertSame(line, block.rows().get(0).text());
    }
}
