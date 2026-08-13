package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * What a producer actually does when it fires: one moment reaches BOTH engines, and each advances
 * whatever it has authored against that kind.
 *
 * <p>Driven through the engine-facing half of {@link ProgressDispatch}, so the mechanism under test
 * is the real one while the ECS half - which only turns an event into a store, a ref and a
 * {@code PlayerRef} - stays for in-game smoke. Both engines run over an in-memory store with native
 * events off; there is no event bus in a unit JVM.
 */
class ProgressDispatchTest {

    private static final String BREAK_BLOCK = "BREAK_BLOCK";
    private static final String KILL_ENTITY = "KILL_ENTITY";

    private Subject player;
    private QuestEngine quests;
    private AchievementEngine achievements;

    @BeforeEach
    void setUp() {
        player = Subject.of(UUID.randomUUID(), "tester");
        quests = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
    }

    @Nonnull
    private static ObjectiveDef objective(@Nonnull String id, @Nonnull String kind,
            @Nonnull String target, long amount) {
        return ObjectiveDef.builder(id, kind)
                .target(target).matchMode(MatchMode.EXACT).amount(amount).build();
    }

    @Test
    void oneFiredMomentReachesBothEngines() {
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        Achievement achievement = Achievement.builder("a_lumberjack")
                .criterion(objective("0", BREAK_BLOCK, "Oak_Log", 5))
                .build();
        quests.setQuests(List.of(quest));
        achievements.setAchievements(List.of(achievement));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, BREAK_BLOCK, "Oak_Log", null, 2L);

        assertNotNull(quests.progressOf(player, "q_gather", "logs"));
        assertEquals(2, quests.progressOf(player, "q_gather", "logs").current());
        assertEquals(2, achievements.progressOf(player, achievement, 0).current());
    }

    @Test
    void aMomentNothingIsWaitingOnChangesNothing() {
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, KILL_ENTITY, "Oak_Log", null, 4L);
        ProgressDispatch.dispatch(quests, achievements, player, BREAK_BLOCK, "Birch_Log", null, 4L);

        ObjectiveProgressState state = quests.progressOf(player, "q_gather", "logs");
        assertTrue(state == null || state.current() == 0,
                "a different kind and a different target are both simply not this objective");
        assertEquals(QuestStatus.ACTIVE, quests.status(player, quest));
    }

    @Test
    void enoughFiredMomentsFinishTheQuest() {
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .autoClaim(false)
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, BREAK_BLOCK, "Oak_Log", null, 3L);

        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, quests.status(player, quest));
    }

    @Test
    void anAbsentEngineIsSkippedRatherThanThrown() {
        Achievement achievement = Achievement.builder("a_lumberjack")
                .criterion(objective("0", BREAK_BLOCK, "Oak_Log", 5))
                .build();
        achievements.setAchievements(List.of(achievement));

        ProgressDispatch.dispatch(null, achievements, player, BREAK_BLOCK, "Oak_Log", null, 1L);

        assertEquals(1, achievements.progressOf(player, achievement, 0).current(),
                "a half-built runtime still feeds the half that exists");
    }
}
