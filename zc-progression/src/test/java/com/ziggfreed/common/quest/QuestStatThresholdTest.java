package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.StatThresholdProbe;
import com.ziggfreed.common.subject.Subject;

/**
 * The standing-value objective: the one built-in kind that describes a STATE rather than a moment,
 * and therefore the one the engine has to go and read for itself.
 *
 * <p>The vocabulary here is a fake registry over a plain map, which is all the engine ever sees: it
 * asks one factor id with the objective's target as the argument and gets a number or nothing back.
 * That keeps every assertion about the ENGINE's rules (when it reads, what it writes, what it
 * refuses to undo) rather than about any particular provider.
 */
class QuestStatThresholdTest {

    private static final String CHANNEL = "Deep_Delving";

    private InMemoryQuestProgressStore store;
    private Subject player;
    private Map<String, Double> channels;
    private List<Object> payloadsSeen;

    @BeforeEach
    void setUp() {
        store = new InMemoryQuestProgressStore();
        player = Subject.of(UUID.randomUUID(), "tester");
        channels = new HashMap<>();
        payloadsSeen = new ArrayList<>();
    }

    // ==================== Harness ====================

    /** A vocabulary answering the one factor a threshold objective is read through. */
    @Nonnull
    private FactorRegistry factors() {
        FactorRegistry registry = new FactorRegistry("test");
        // No asset-defined layer in a unit run: the answers must come from this map alone.
        registry.derivedSource(null);
        registry.register(StatThresholdProbe.STAT_FACTOR, "test", ctx -> {
            payloadsSeen.add(ctx.payload());
            return ctx.param() == null ? null : channels.get(ctx.param());
        });
        return registry;
    }

    @Nonnull
    private QuestEngine.Builder unwired() {
        return QuestEngine.builder()
                .store(store)
                .nativeEvents(false)
                .warn(message -> { });
    }

    @Nonnull
    private QuestEngine wired() {
        return unwired()
                .factors(factors())
                .factorContext(subject -> FactorContext.builder().payload(subject.id()).build())
                .build();
    }

    @Nonnull
    private static ObjectiveDef threshold(@Nonnull String id, long amount) {
        return ObjectiveDef.builder(id, ObjectiveKindRegistry.STAT_THRESHOLD)
                .target(CHANNEL).matchMode(MatchMode.EXACT).amount(amount).build();
    }

    @Nonnull
    private static Quest thresholdQuest(@Nonnull String id, long amount) {
        return Quest.builder(id).objective(threshold("reach", amount)).build();
    }

    private int currentOf(@Nonnull QuestEngine engine, @Nonnull String questId,
                          @Nonnull String objectiveId) {
        ObjectiveProgressState state = engine.progressOf(player, questId, objectiveId);
        assertNotNull(state, "the objective should have a seeded progress entry");
        return state.current();
    }

    // ==================== Accept ====================

    @Nested
    class OnAccept {

        @Test
        void anAlreadyMetThresholdIsSatisfiedTheMomentTheQuestIsTaken() {
            channels.put(CHANNEL, 12d);
            QuestEngine engine = wired();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));

            assertTrue(engine.accept(player, engine.quest("q_reach")));

            assertEquals(10, currentOf(engine, "q_reach", "reach"),
                    "a reading past the threshold seeds it at the threshold, never above");
            assertTrue(engine.allObjectivesComplete(player, engine.quest("q_reach")));
        }

        @Test
        void theConsumersOwnPreSatisfiedAnswerAndTheReadingFoldAsTheLargerOfTheTwo() {
            channels.put(CHANNEL, 3d);
            QuestEngine engine = unwired()
                    .factors(factors())
                    .factorContext(subject -> FactorContext.builder().payload(subject.id()).build())
                    .gates(new QuestGates() {
                        @Override
                        public long preSatisfiedAmount(@Nonnull Subject subject, @Nonnull Quest quest,
                                                       @Nonnull ObjectiveDef objective) {
                            return 7L;
                        }
                    })
                    .build();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));

            engine.accept(player, engine.quest("q_reach"));

            assertEquals(7, currentOf(engine, "q_reach", "reach"),
                    "both are high-water values, so seeding with the larger is seeding with both");
        }

        @Test
        void theSubjectReachesTheProviderThroughTheWiredContext() {
            channels.put(CHANNEL, 4d);
            QuestEngine engine = wired();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));

            engine.accept(player, engine.quest("q_reach"));

            assertTrue(payloadsSeen.contains(player.id()),
                    "the context function is what carries WHO the question is about");
        }

        @Test
        void aFractionalReadingIsFlooredSoItCannotRoundIntoACompletion() {
            channels.put(CHANNEL, 9.99d);
            QuestEngine engine = wired();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));

            engine.accept(player, engine.quest("q_reach"));

            assertEquals(9, currentOf(engine, "q_reach", "reach"));
            assertFalse(engine.allObjectivesComplete(player, engine.quest("q_reach")));
        }
    }

    // ==================== High-water and unreadable values ====================

    @Nested
    class NeverGoesBackwards {

        @Test
        void aLaterLowerReadingLeavesRecordedProgressAlone() {
            channels.put(CHANNEL, 6d);
            QuestEngine engine = wired();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));
            engine.accept(player, engine.quest("q_reach"));
            assertEquals(6, currentOf(engine, "q_reach", "reach"));

            channels.put(CHANNEL, 2d);
            engine.selfHeal(player);

            assertEquals(6, currentOf(engine, "q_reach", "reach"));
        }

        @Test
        void aChannelNothingCanAnswerWritesNothingRatherThanResetting() {
            channels.put(CHANNEL, 6d);
            QuestEngine engine = wired();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));
            engine.accept(player, engine.quest("q_reach"));

            channels.remove(CHANNEL);
            int changed = engine.selfHeal(player);

            assertEquals(0, changed, "nothing to apply is nothing to write");
            assertEquals(6, currentOf(engine, "q_reach", "reach"));
        }

        @Test
        void aQuestTakenWithNothingReadableSimplyStartsAtNothing() {
            QuestEngine engine = wired();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));

            assertTrue(engine.accept(player, engine.quest("q_reach")));

            assertEquals(0, currentOf(engine, "q_reach", "reach"));
            assertEquals(QuestStatus.ACTIVE, engine.status(player, "q_reach"));
        }
    }

    // ==================== The two re-check moments ====================

    @Nested
    class WhenTheEngineReReads {

        @Test
        void aDispatchToASiblingStepReReadsTheThresholdOnTheSameQuest() {
            QuestEngine engine = wired();
            Quest quest = Quest.builder("q_mixed")
                    .objective(ObjectiveDef.builder("logs", "BREAK_BLOCK")
                            .target("Oak_Log").matchMode(MatchMode.EXACT).amount(2).build())
                    .objective(threshold("reach", 5))
                    .build();
            engine.setQuests(List.of(quest));
            engine.accept(player, quest);
            assertEquals(0, currentOf(engine, "q_mixed", "reach"));

            channels.put(CHANNEL, 5d);
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            assertEquals(5, currentOf(engine, "q_mixed", "reach"),
                    "a quest that just moved is the cheap moment to re-read its threshold step");
            assertEquals(QuestStatus.ACTIVE, engine.status(player, "q_mixed"),
                    "the other step is still outstanding, so nothing settles yet");

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
            assertEquals(QuestStatus.COMPLETED, engine.status(player, "q_mixed"));
        }

        @Test
        void aDispatchTouchingAnotherQuestEntirelyDoesNotReReadThisOne() {
            QuestEngine engine = wired();
            engine.setQuests(List.of(
                    thresholdQuest("q_reach", 5),
                    Quest.builder("q_chop")
                            .objective(ObjectiveDef.builder("logs", "BREAK_BLOCK")
                                    .target("Oak_Log").matchMode(MatchMode.EXACT).amount(5).build())
                            .build()));
            engine.accept(player, engine.quest("q_reach"));
            engine.accept(player, engine.quest("q_chop"));

            channels.put(CHANNEL, 5d);
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            assertEquals(0, currentOf(engine, "q_reach", "reach"),
                    "the re-check rides on the quest that moved, it is not a sweep over everything");
        }

        @Test
        void selfHealSettlesAThresholdThatWasMetWhileNobodyWasWatching() {
            QuestEngine engine = wired();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));
            engine.accept(player, engine.quest("q_reach"));

            channels.put(CHANNEL, 10d);
            int changed = engine.selfHeal(player);

            assertTrue(changed > 0);
            assertEquals(QuestStatus.COMPLETED, engine.status(player, "q_reach"),
                    "meeting the last step settles the quest, whichever way the step was met");
        }

        @Test
        void aQuestNotBeingCarriedIsNeverTouchedBySelfHeal() {
            QuestEngine engine = wired();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));
            channels.put(CHANNEL, 10d);

            assertEquals(0, engine.selfHeal(player));
            assertEquals(QuestStatus.NOT_STARTED, engine.status(player, "q_reach"));
        }

        @Test
        void aStepStillLockedByOrderIsNotReadUntilItsTurnComesRound() {
            QuestEngine engine = wired();
            Quest quest = Quest.builder("q_ordered")
                    .objective(ObjectiveDef.builder("logs", "BREAK_BLOCK")
                            .target("Oak_Log").matchMode(MatchMode.EXACT).amount(1).order(1).build())
                    .objective(ObjectiveDef.builder("reach", ObjectiveKindRegistry.STAT_THRESHOLD)
                            .target(CHANNEL).matchMode(MatchMode.EXACT).amount(5).order(2).build())
                    .build();
            engine.setQuests(List.of(quest));
            engine.accept(player, quest);

            channels.put(CHANNEL, 5d);
            engine.selfHeal(player);
            assertEquals(0, currentOf(engine, "q_ordered", "reach"),
                    "a locked step measures nothing, so it costs nothing either");

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
            assertEquals(QuestStatus.COMPLETED, engine.status(player, "q_ordered"),
                    "the step that unlocked it is written first, so the same pass reads it");
        }
    }

    // ==================== The unwired seam ====================

    @Nested
    class WithNoFactorVocabulary {

        @Test
        void theKindIsPurelyConsumerFiredAndStillTracksAHighWaterValue() {
            channels.put(CHANNEL, 10d);
            QuestEngine engine = unwired().build();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));
            engine.accept(player, engine.quest("q_reach"));

            assertEquals(0, currentOf(engine, "q_reach", "reach"), "nothing is read for itself");
            assertEquals(0, engine.selfHeal(player));

            engine.dispatch(player, ObjectiveKindRegistry.STAT_THRESHOLD, CHANNEL, null, 7);
            assertEquals(7, currentOf(engine, "q_reach", "reach"));

            engine.dispatch(player, ObjectiveKindRegistry.STAT_THRESHOLD, CHANNEL, null, 3);
            assertEquals(7, currentOf(engine, "q_reach", "reach"),
                    "the kind is value-based, so a lower fire is not added on top");

            engine.dispatch(player, ObjectiveKindRegistry.STAT_THRESHOLD, CHANNEL, null, 10);
            assertEquals(QuestStatus.COMPLETED, engine.status(player, "q_reach"));
        }

        @Test
        void wiringOnlyAContextWithNoVocabularyChangesNothing() {
            channels.put(CHANNEL, 10d);
            QuestEngine engine = unwired()
                    .factorContext(subject -> FactorContext.builder().payload(subject.id()).build())
                    .build();
            engine.setQuests(List.of(thresholdQuest("q_reach", 10)));

            engine.accept(player, engine.quest("q_reach"));

            assertEquals(0, currentOf(engine, "q_reach", "reach"));
            assertTrue(payloadsSeen.isEmpty(), "with no registry there is nothing to ask");
        }
    }
}
