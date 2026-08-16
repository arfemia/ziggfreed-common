package com.ziggfreed.common.progress.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.quest.asset.AssetQuestGates;
import com.ziggfreed.common.subject.Subject;

/**
 * The four progression readings: what each answers, and - the half that decides whether content
 * gated on them is safe - what each REFUSES to answer.
 *
 * <p>The case that matters most is an id nothing knows. A mistyped quest id answering {@code 0}
 * would read as "they have not done it", which passes a bounds-less gate and hands out the content
 * the author meant to lock; answering nothing keeps it shut. Every "unknown" case here is that rule.
 *
 * <p>The reads are driven two ways: through a hand-written double, so each ladder can be walked
 * rung by rung, and through the REAL shared runtime over in-memory stores, so the answers are the
 * engines' own rather than a restatement of them.
 */
class ProgressionFactorsTest {

    private static final Subject PLAYER =
            Subject.of(UUID.nameUUIDFromBytes("player".getBytes()), "Player");

    private FactorRegistry factors;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        factors = new FactorRegistry("test");
        factors.derivedSource(null);
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    // ==================== the doubles ====================

    /** A subject source that always answers the same player, so the reads can be exercised alone. */
    private static final ProgressionFactors.Subjects ALWAYS = new ProgressionFactors.Subjects() {

        @Override
        @Nullable
        public Subject questSubject(@Nonnull FactorContext ctx) {
            return PLAYER;
        }

        @Override
        @Nullable
        public Subject achievementSubject(@Nonnull FactorContext ctx) {
            return PLAYER;
        }
    };

    /** The no-player case every evaluation site hits somewhere: a placement sweep, an audit. */
    private static final ProgressionFactors.Subjects NOBODY = new ProgressionFactors.Subjects() {

        @Override
        @Nullable
        public Subject questSubject(@Nonnull FactorContext ctx) {
            return null;
        }

        @Override
        @Nullable
        public Subject achievementSubject(@Nonnull FactorContext ctx) {
            return null;
        }
    };

    /** Fixture reads: one finished quest, one catalogued-but-unfinished, one earned achievement. */
    private static final class FakeReads implements ProgressionFactors.Reads {

        private final List<String> knownQuests = new ArrayList<>(List.of("done", "todo"));
        private boolean recordsCompletions = true;
        private int completions = 3;
        private final List<String> knownAchievements = new ArrayList<>(List.of("earned", "locked"));
        private int points = 40;

        @Override
        public boolean questKnown(@Nonnull String questId) {
            return knownQuests.contains(questId);
        }

        @Override
        public boolean questFinished(@Nonnull Subject subject, @Nonnull String questId) {
            return "done".equals(questId);
        }

        @Override
        @Nullable
        public Integer questCompletions(@Nonnull Subject subject, @Nonnull String questId) {
            if (!recordsCompletions) {
                return null;
            }
            return Integer.valueOf("done".equals(questId) ? completions : 0);
        }

        @Override
        public boolean achievementKnown(@Nonnull String achievementId) {
            return knownAchievements.contains(achievementId);
        }

        @Override
        public boolean achievementEarned(@Nonnull Subject subject, @Nonnull String achievementId) {
            return "earned".equals(achievementId);
        }

        @Override
        public int achievementPoints(@Nonnull Subject subject) {
            return points;
        }
    }

    private FactorRegistry vocabulary(@Nonnull ProgressionFactors.Subjects subjects,
                                      @Nonnull ProgressionFactors.Reads reads) {
        ProgressionFactors.registerInto(factors, "test", subjects, reads);
        return factors;
    }

    @Nullable
    private Double resolve(@Nonnull String factorId, @Nullable String param) {
        return factors.resolve(factorId, FactorContext.builder().param(param).build());
    }

    // ==================== what each id answers ====================

    @Nested
    class TheReadings {

        @BeforeEach
        void registered() {
            vocabulary(ALWAYS, new FakeReads());
        }

        @Test
        void aFinishedQuestReadsOneAndAnUnfinishedOneReadsZero() {
            assertEquals(1.0, resolve(ProgressionFactors.QUEST_COMPLETED, "done"));
            assertEquals(0.0, resolve(ProgressionFactors.QUEST_COMPLETED, "todo"));
        }

        @Test
        void completionsAnswerTheLifetimeCount() {
            assertEquals(3.0, resolve(ProgressionFactors.QUEST_COMPLETIONS, "done"));
            assertEquals(0.0, resolve(ProgressionFactors.QUEST_COMPLETIONS, "todo"));
        }

        @Test
        void anEarnedAchievementReadsOneAndALockedOneReadsZero() {
            assertEquals(1.0, resolve(ProgressionFactors.ACHIEVEMENT_EARNED, "earned"));
            assertEquals(0.0, resolve(ProgressionFactors.ACHIEVEMENT_EARNED, "locked"));
        }

        @Test
        void pointsAnswerTheTotalAndIgnoreParam() {
            assertEquals(40.0, resolve(ProgressionFactors.ACHIEVEMENT_POINTS, null));
            assertEquals(40.0, resolve(ProgressionFactors.ACHIEVEMENT_POINTS, "anything"),
                    "a points total is one number for the player - Param has nothing to address");
        }
    }

    // ==================== what each id refuses to answer ====================

    @Nested
    class FailClosed {

        @Test
        void anIdNothingKnowsReadsNothingRatherThanZero() {
            vocabulary(ALWAYS, new FakeReads());

            assertNull(resolve(ProgressionFactors.QUEST_COMPLETED, "typo"),
                    "a mistyped quest id must not read as 'not done'");
            assertNull(resolve(ProgressionFactors.QUEST_COMPLETIONS, "typo"));
            assertNull(resolve(ProgressionFactors.ACHIEVEMENT_EARNED, "typo"));
        }

        @Test
        void aBoundsLessGateOnAnUnknownIdStaysShut() {
            vocabulary(ALWAYS, new FakeReads());

            FactorCondition presence =
                    FactorCondition.of(ProgressionFactors.QUEST_COMPLETED, "typo", null, null);
            assertFalse(presence.accepts(resolve(ProgressionFactors.QUEST_COMPLETED, "typo")),
                    "the bounds-less form is how 'only where that content exists' is written");
            assertTrue(presence.accepts(resolve(ProgressionFactors.QUEST_COMPLETED, "todo")));
        }

        @Test
        void noParamReadsNothingOnEveryIdThatAddressesOne() {
            vocabulary(ALWAYS, new FakeReads());

            for (String id : List.of(ProgressionFactors.QUEST_COMPLETED,
                    ProgressionFactors.QUEST_COMPLETIONS, ProgressionFactors.ACHIEVEMENT_EARNED)) {
                assertNull(resolve(id, null), id + " has nothing to answer about with no Param");
                assertNull(resolve(id, "  "), id + " must not treat blank as an id");
            }
        }

        @Test
        void noPlayerInTheQuestionReadsNothingOnEveryId() {
            vocabulary(NOBODY, new FakeReads());

            assertNull(resolve(ProgressionFactors.QUEST_COMPLETED, "done"));
            assertNull(resolve(ProgressionFactors.QUEST_COMPLETIONS, "done"));
            assertNull(resolve(ProgressionFactors.ACHIEVEMENT_EARNED, "earned"));
            assertNull(resolve(ProgressionFactors.ACHIEVEMENT_POINTS, null),
                    "a points bound must fail closed where there is nobody to count for");
        }

        @Test
        void aStoreThatCannotRememberCompletionsSaysSoRatherThanCountingZero() {
            FakeReads reads = new FakeReads();
            reads.recordsCompletions = false;
            vocabulary(ALWAYS, reads);

            assertNull(resolve(ProgressionFactors.QUEST_COMPLETIONS, "done"),
                    "reporting every player as zero would pass a 'fewer than N' bound for everybody");
            assertEquals(1.0, resolve(ProgressionFactors.QUEST_COMPLETED, "done"),
                    "whether it was finished is a different question, and this store still knows it");
        }

        @Test
        void nothingIsAnsweredBeforeTheRuntimeIsBuilt() {
            ProgressionFactors.registerInto(factors, "test",
                    ProgressionFactors.Subjects.RUNTIME, ProgressionFactors.Reads.RUNTIME);

            assertFalse(ProgressionRuntime.isBuilt());
            assertNull(resolve(ProgressionFactors.QUEST_COMPLETED, "done"));
            assertNull(resolve(ProgressionFactors.ACHIEVEMENT_POINTS, null));
            assertFalse(ProgressionRuntime.isBuilt(),
                    "a factor read must never be what seals the runtime");
        }
    }

    // ==================== over the real engines ====================

    @Nested
    class OverTheSharedRuntime {

        private InMemoryQuestProgressStore quests;
        private InMemoryAchievementProgressStore achievements;

        @BeforeEach
        void buildRuntime() {
            quests = new InMemoryQuestProgressStore();
            achievements = new InMemoryAchievementProgressStore();
            ProgressionRuntime.defaults("test")
                    .questStore(quests)
                    .achievementStore(achievements);
            ProgressionRuntime.publishQuests("test", List.of(
                    Quest.builder("done").build(),
                    Quest.builder("todo").build(),
                    Quest.builder("uncollected").build()));
            ProgressionRuntime.publishAchievements("test", List.of(
                    Achievement.builder("earned").points(25).build(),
                    Achievement.builder("locked").points(15).build()));
            ProgressionRuntime.ensureBuilt();

            quests.setStatus(PLAYER, "done", QuestStatus.COMPLETED);
            quests.setCompletions(PLAYER, "done",
                    new QuestProgressStore.CompletionRecord(1L, 1, 4));
            achievements.setStatus(PLAYER, "earned", AchievementStatus.UNLOCKED);

            vocabulary(ALWAYS, ProgressionFactors.Reads.RUNTIME);
        }

        @Test
        void theEnginesOwnAnswersComeBackAsNumbers() {
            assertEquals(1.0, resolve(ProgressionFactors.QUEST_COMPLETED, "done"));
            assertEquals(0.0, resolve(ProgressionFactors.QUEST_COMPLETED, "todo"));
            assertEquals(4.0, resolve(ProgressionFactors.QUEST_COMPLETIONS, "done"));
            assertEquals(1.0, resolve(ProgressionFactors.ACHIEVEMENT_EARNED, "earned"));
            assertEquals(0.0, resolve(ProgressionFactors.ACHIEVEMENT_EARNED, "locked"));
            assertEquals(25.0, resolve(ProgressionFactors.ACHIEVEMENT_POINTS, null),
                    "only what has been earned counts toward the total");
        }

        @Test
        void aQuestNoCatalogueCarriesStillReadsNothing() {
            assertNull(resolve(ProgressionFactors.QUEST_COMPLETED, "another_mods_quest"));
            assertNull(resolve(ProgressionFactors.ACHIEVEMENT_EARNED, "another_mods_achievement"));
        }

        @Test
        void aCollectedAchievementStillCountsAsEarned() {
            achievements.setStatus(PLAYER, "earned", AchievementStatus.CLAIMED);

            assertEquals(1.0, resolve(ProgressionFactors.ACHIEVEMENT_EARNED, "earned"),
                    "taking the reward is not un-earning it");
            assertEquals(25.0, resolve(ProgressionFactors.ACHIEVEMENT_POINTS, null));
        }

        /**
         * The whole point of a completion FACTOR: the same requirement written the two ways a
         * server can write it has to mean one thing. A prerequisite leaf and a factor condition are
         * answered by two different pieces of code, so the agreement is pinned rather than assumed.
         */
        @Test
        void theQuestsPrerequisiteLeafAndTheFactorAgree() {
            GateEvaluator gate = GateEvaluator.builder().factors(factors).build();
            AssetQuestGates gates = AssetQuestGates.of(gate);
            gates.useEngine(ProgressionRuntime.quests());

            assertTrue(gate.passes(PLAYER, requiresQuests("done")));
            assertEquals(1.0, resolve(ProgressionFactors.QUEST_COMPLETED, "done"));

            assertFalse(gate.passes(PLAYER, requiresQuests("todo")));
            assertEquals(0.0, resolve(ProgressionFactors.QUEST_COMPLETED, "todo"));
        }

        /**
         * The half of that agreement it is easiest to get wrong. A quest whose objectives are done
         * but whose reward is still sitting there uncollected - where an {@code AutoClaim: false}
         * quest lives between finishing it and taking the payout - is not yet a prerequisite,
         * whichever way the requirement was written.
         */
        @Test
        void aQuestFinishedButNotCollectedIsNotYetAPrerequisite() {
            GateEvaluator gate = GateEvaluator.builder().factors(factors).build();
            AssetQuestGates gates = AssetQuestGates.of(gate);
            gates.useEngine(ProgressionRuntime.quests());
            quests.setStatus(PLAYER, "uncollected", QuestStatus.COMPLETED_UNCLAIMED);

            assertFalse(gate.passes(PLAYER, requiresQuests("uncollected")),
                    "the leaf must wait for the reward to be collected");
            assertEquals(0.0, resolve(ProgressionFactors.QUEST_COMPLETED, "uncollected"),
                    "and the factor must say the same thing about the same player");

            quests.setStatus(PLAYER, "uncollected", QuestStatus.COMPLETED);

            assertTrue(gate.passes(PLAYER, requiresQuests("uncollected")));
            assertEquals(1.0, resolve(ProgressionFactors.QUEST_COMPLETED, "uncollected"));
        }

        @Test
        void aFinishedRepeatableOnCooldownStillCountsAsFinished() {
            quests.setCooldownStamp(PLAYER, "done", System.currentTimeMillis());

            assertEquals(1.0, resolve(ProgressionFactors.QUEST_COMPLETED, "done"),
                    "'have you ever done this' is what a requirement asks");
        }
    }

    // ==================== the process-wide claim ====================

    @Nested
    class TheContribution {

        @Test
        void contributeClaimsAllFourIdsForEveryVocabularyOnTheServer() {
            ProgressionFactors.contribute();

            for (String id : List.of(ProgressionFactors.QUEST_COMPLETED,
                    ProgressionFactors.QUEST_COMPLETIONS, ProgressionFactors.ACHIEVEMENT_EARNED,
                    ProgressionFactors.ACHIEVEMENT_POINTS)) {
                assertTrue(FactorContributions.isContributed(id), id + " must be claimed once");
                FactorRegistry other = new FactorRegistry("somebody-else");
                other.derivedSource(null);
                assertTrue(other.isRegistered(id),
                        "a vocabulary that registered nothing still resolves a contributed id");
                assertNull(other.resolve(id, FactorContext.builder().param("done").build()),
                        "and with no runtime built and no player, it answers nothing");
            }
            assertEquals(List.of(ProgressionFactors.ACHIEVEMENT_EARNED,
                            ProgressionFactors.ACHIEVEMENT_POINTS,
                            ProgressionFactors.QUEST_COMPLETED,
                            ProgressionFactors.QUEST_COMPLETIONS),
                    FactorContributions.contributors().get(ProgressionFactors.OWNER),
                    "the contributed set is fixed - a new id here is a deliberate vocabulary addition");
        }
    }

    // ==================== helpers ====================

    /** A {@code Requires} block asking for one finished quest, decoded exactly as content is. */
    @Nonnull
    private static GateSpec requiresQuests(@Nonnull String questId) {
        try {
            return GateSpec.CODEC.decodeJson(
                    RawJsonReader.fromJsonString("{ \"Quests\": [\"" + questId + "\"] }"),
                    new ExtraInfo());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
