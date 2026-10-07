package com.ziggfreed.common.objectives.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.book.SeenMarks;
import com.ziggfreed.common.objectives.book.achievement.AchievementReader;
import com.ziggfreed.common.objectives.journal.QuestPresentation;
import com.ziggfreed.common.objectives.journal.QuestReader;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Pill;

/**
 * What the pinned-and-tracked panel lists, read without a page: the player's pinned achievements as compact rows in
 * pin order, then the quests they track that are still being carried; each side capped, an empty side left out, and
 * nothing at all when neither engine can be read.
 */
class ObjectivePanelsTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final Subject ALICE = new Subject(new UUID(0, 7), "Alice", null);

    private AchievementEngine achievements;
    private QuestEngine quests;

    @BeforeEach
    void engines() {
        ProgressionRuntime.resetForTests();
        achievements = AchievementEngine.builder().nativeEvents(false).clock(() -> NOW).build();
        quests = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> true).maxActive(10).maxTracked(3).clock(() -> NOW).build();
    }

    @AfterEach
    void reset() {
        ProgressionRuntime.resetForTests();
    }

    private static Achievement ach(@Nonnull String id) {
        return Achievement.builder(id).category("combat")
                .criterion(ObjectiveDef.builder("0", "BREAK_BLOCK").amount(10).build()).build();
    }

    private static Quest quest(@Nonnull String id) {
        return Quest.builder(id).category("main")
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build();
    }

    private void catalogue(@Nonnull Achievement... list) {
        achievements.setAchievements(List.of(list));
    }

    private void pin(@Nonnull Achievement a, long at) {
        achievements.store().setPin(ALICE, a.id(), at);
    }

    private AchievementReader achievementReader() {
        return AchievementReader.of(achievements, ALICE, ObjectiveBookDeps.DEFAULTS, ALICE.id(),
                OccurrenceSource.NONE, SeenMarks.NONE, NOW);
    }

    private QuestReader questReader() {
        return QuestReader.of(quests, ALICE, NO_PRESENTATION, ALICE.id(), NOW);
    }

    @Nonnull
    private static List<String> ids(@Nonnull LedgerSection section) {
        List<String> out = new ArrayList<>();
        for (LedgerRow row : section.rows()) {
            out.add(row.id());
        }
        return out;
    }

    @Test
    void pinnedAchievementsComeFirstThenTrackedQuests() {
        Achievement older = ach("a_older");
        Achievement newer = ach("a_newer");
        catalogue(newer, older);
        pin(newer, NOW - 10);
        pin(older, NOW - 1000);
        Quest carried = quest("q_carried");
        quests.setQuests(List.of(carried));
        assertTrue(quests.accept(ALICE, carried));
        assertTrue(quests.track(ALICE, carried.id()));

        List<LedgerSection> sections = ObjectivePanels.sections(achievementReader(), questReader(), 5);

        assertEquals(2, sections.size());
        assertEquals(ObjectivePanels.PINNED, sections.get(0).id());
        assertEquals(List.of("a_older", "a_newer"), ids(sections.get(0)), "oldest pin first, as the engine keeps them");
        assertEquals(ObjectivePanels.TRACKED, sections.get(1).id());
        assertEquals(List.of("q_carried"), ids(sections.get(1)));
        assertEquals("ziggfreedcommon.progression.book.achievements.overview.pinned",
                sections.get(0).label().getMessageId());
        assertEquals("ziggfreedcommon.progression.book.quests.tracked_header", sections.get(1).label().getMessageId());
        for (LedgerSection section : sections) {
            assertTrue(section.openByDefault(), "the panel's sections are always open");
            for (LedgerRow row : section.rows()) {
                assertEquals(null, row.meta(), "the panel paints compact rows, which carry no meta line");
            }
        }
    }

    @Test
    void eachSideIsCappedAtMaxRows() {
        Achievement a = ach("a_one");
        Achievement b = ach("a_two");
        Achievement c = ach("a_three");
        catalogue(a, b, c);
        pin(a, NOW - 3);
        pin(b, NOW - 2);
        pin(c, NOW - 1);

        List<LedgerSection> sections = ObjectivePanels.sections(achievementReader(), questReader(), 2);

        assertEquals(1, sections.size(), "no tracked quest, so only the pinned side");
        assertEquals(List.of("a_one", "a_two"), ids(sections.get(0)));
    }

    @Test
    void anEmptySideIsLeftOut() {
        catalogue(ach("a_unpinned"));
        Quest carried = quest("q_carried");
        quests.setQuests(List.of(carried));
        assertTrue(quests.accept(ALICE, carried));
        assertTrue(quests.track(ALICE, carried.id()));

        List<LedgerSection> sections = ObjectivePanels.sections(achievementReader(), questReader(), 5);

        assertEquals(1, sections.size());
        assertEquals(ObjectivePanels.TRACKED, sections.get(0).id());
        assertTrue(ObjectivePanels.sections(achievementReader(), null, 5).isEmpty(), "nothing pinned, no quests read");
    }

    @Test
    void aQuestTrackedButNoLongerCarriedIsNotListed() {
        Quest finished = quest("q_finished");
        quests.setQuests(List.of(finished));
        assertTrue(quests.accept(ALICE, finished));
        assertTrue(quests.track(ALICE, finished.id()));
        quests.markCompleted(ALICE, finished);

        assertTrue(ObjectivePanels.sections(null, questReader(), 5).isEmpty());
    }

    @Test
    void anEarnedPinStaysListedLikeTheOverviewsPinnedStrip() {
        Achievement earned = ach("a_earned");
        catalogue(earned);
        pin(earned, NOW - 5);
        achievements.store().setStatus(ALICE, earned.id(), AchievementStatus.CLAIMED);
        achievements.store().setUnlockedAt(ALICE, earned.id(), NOW - 1);

        List<LedgerSection> sections = ObjectivePanels.sections(achievementReader(), null, 5);
        List<LedgerRow> overviewPinned = achievementReader().overview(0, 0).pinned();

        assertEquals(overviewPinned.size(), sections.isEmpty() ? 0 : sections.get(0).rows().size(),
                "the panel lists exactly what the book's Pinned strip lists");
    }

    @Test
    void noReadersAndNoRowsListNothing() {
        assertTrue(ObjectivePanels.sections(null, null, 5).isEmpty());
        catalogue(ach("a_pinned"));
        pin(ach("a_pinned"), NOW);
        assertTrue(ObjectivePanels.sections(achievementReader(), null, 0).isEmpty(), "zero rows asked, none listed");
    }

    /** A consumer saying nothing: no board, no pills, no hints. */
    private static final QuestPresentation NO_PRESENTATION = new QuestPresentation() {
        @Override
        public boolean managed(@Nonnull Quest q) {
            return false;
        }

        @Nonnull
        @Override
        public List<Pill> pills(@Nonnull Quest q) {
            return List.of();
        }

        @Nullable
        @Override
        public Message acceptHint(@Nonnull Quest q) {
            return null;
        }

        @Nullable
        @Override
        public Message requirementLine(@Nonnull Quest q) {
            return null;
        }

        @Nonnull
        @Override
        public Message tagLabel(@Nonnull String tag) {
            return Message.raw(tag);
        }

        @Nullable
        @Override
        public Message claimPreCheck(@Nonnull Quest q, @Nonnull Store<EntityStore> s, @Nonnull Ref<EntityStore> r,
                @Nonnull Player p) {
            return null;
        }

        @Nullable
        @Override
        public Message npcName(@Nullable String npcId) {
            return null;
        }
    };
}
