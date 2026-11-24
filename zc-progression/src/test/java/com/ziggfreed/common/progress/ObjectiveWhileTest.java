package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.quest.asset.QuestPool;
import com.ziggfreed.common.quest.asset.QuestPoolValidator;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.validation.Finding;

/**
 * A step's own condition ({@code While}): decoded onto the step, asked at every credit through the server's one
 * evaluator, and never asked of a step that writes none. A moment it refuses is not lost to anything else.
 */
class ObjectiveWhileTest {

    private static final String SWORN = "yourmod:sworn";

    private final AtomicBoolean sworn = new AtomicBoolean();
    /** How many times the server's evaluator reached for its vocabulary: every gate it asks does, at least once. */
    private final AtomicInteger evaluatorAsks = new AtomicInteger();
    private final Subject player = Subject.of(UUID.randomUUID(), "tester");

    @BeforeEach
    void installVocabulary() {
        ProgressionGates.resetForTests();
        FactorRegistry vocabulary = new FactorRegistry("test");
        vocabulary.register(SWORN, "test", ctx -> sworn.get() ? 1.0 : 0.0);
        GateRuntime.install(() -> {
            evaluatorAsks.incrementAndGet();
            return vocabulary;
        }, null, null, null);
    }

    @AfterEach
    void resetVocabulary() {
        ProgressionGates.resetForTests();
    }

    private static GateSpec whileSworn() {
        return GateSpec.of(new FactorCondition[] {FactorCondition.of(SWORN, null, 1.0, null)},
                null, null, null, null, null, null);
    }

    private static ObjectiveDef kill(String id, long amount, GateSpec condition) {
        return ObjectiveDef.builder(id, "KILL_ENTITY").amount(amount).countsWhile(condition).build();
    }

    // ==================== decode ====================

    @Nested
    class Decode {

        private QuestAsset quest(String json, String id, String parentId, QuestAsset parent) throws IOException {
            AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, id, parentId);
            return QuestAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                    new AssetExtraInfo<>(data));
        }

        @Test
        void aStepCarriesItsWhileOntoTheEnginesStepAndAChildKeepsIt() throws Exception {
            QuestAsset parent = quest("""
                    { "Objectives": {
                        "defeat": { "Kind": "KILL_ENTITY", "Amount": 30,
                                    "While": { "Factors": [ { "Factor": "yourmod:sworn", "Param": "Banner", "Min": 1 } ] } },
                        "plain":  { "Kind": "KILL_ENTITY", "Amount": 3 },
                        "empty":  { "Kind": "KILL_ENTITY", "Amount": 3, "While": {} } } }
                    """, "oath_base", null, null);
            Quest folded = parent.toDefinition(null).quest();

            GateSpec condition = folded.objective("defeat").countsWhile();
            assertNotNull(condition, "the authored block rides the step");
            FactorCondition factor = condition.factorsOrEmpty()[0];
            assertEquals(SWORN, factor.getFactor());
            assertEquals("Banner", factor.getParam());
            assertEquals(Double.valueOf(1.0), factor.getMin());
            assertNull(folded.objective("plain").countsWhile(), "a step that writes none carries none");
            assertNull(folded.objective("empty").countsWhile(), "an empty block reads as none");

            QuestAsset child = quest("""
                    { "Objectives": { "defeat": { "Amount": 40 } } }
                    """, "oath", "oath_base", parent);
            ObjectiveDef inherited = child.toDefinition(null).quest().objective("defeat");
            assertEquals(40L, inherited.amount());
            assertNotNull(inherited.countsWhile(), "the leaf carries down a Parent chain like every other");
        }

        @Test
        void anAchievementCriterionDecodesTheSameLeaf() throws Exception {
            AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementAsset.class, "sworn_slayer", null);
            AchievementAsset asset = AchievementAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString("""
                    { "Criteria": { "slay": { "Kind": "KILL_ENTITY", "Amount": 5,
                                              "While": { "Factors": [ { "Factor": "yourmod:sworn", "Min": 1 } ] } } } }
                    """), null, new AssetExtraInfo<>(data));
            ObjectiveDef criterion = asset.toDefinition().achievement().criteria().get(0);
            assertNotNull(criterion.countsWhile());
            assertEquals(SWORN, criterion.countsWhile().factorsOrEmpty()[0].getFactor());
        }
    }

    // ==================== the quest engine ====================

    @Nested
    class QuestSteps {

        private QuestEngine engine(Quest quest) {
            QuestEngine engine = QuestEngine.builder()
                    .store(new InMemoryQuestProgressStore())
                    .rewardKinds(new RewardKindRegistry())
                    .nativeEvents(false)
                    .warn(message -> { })
                    .build();
            engine.setQuests(List.of(quest));
            return engine;
        }

        @Test
        void aStepCountsOnlyTheMomentsThatComeWhileItsConditionHolds() {
            Quest quest = Quest.builder("q_oath")
                    .objective(kill("sworn_kills", 3, whileSworn()))
                    .objective(kill("any_kills", 3, null))
                    .build();
            QuestEngine engine = engine(quest);
            assertTrue(engine.accept(player, quest));

            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1);
            assertEquals(0, engine.progressOf(player, "q_oath", "sworn_kills").current(),
                    "a kill while the condition does not hold counts nothing toward its step");
            assertEquals(1, engine.progressOf(player, "q_oath", "any_kills").current(),
                    "and is not lost to the step beside it");

            sworn.set(true);
            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1);
            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1);
            assertEquals(2, engine.progressOf(player, "q_oath", "sworn_kills").current());

            sworn.set(false);
            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1);
            assertEquals(2, engine.progressOf(player, "q_oath", "sworn_kills").current(),
                    "breaking the condition stops the count without taking any back");
            assertEquals(3, engine.progressOf(player, "q_oath", "any_kills").current());
        }

        @Test
        void aQuestWhoseStepsWriteNoConditionAsksNothingNew() {
            Quest quest = Quest.builder("q_plain")
                    .objective(kill("kills", 3, null))
                    .build();
            QuestEngine engine = engine(quest);
            engine.accept(player, quest);

            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 2);
            assertEquals(2, engine.progressOf(player, "q_plain", "kills").current(), "counted exactly as before");
            assertEquals(0, evaluatorAsks.get(), "the evaluator is never asked for a step that writes no While");

            assertTrue(!ObjectiveWhile.holds(player, kill("k", 1, whileSworn())) && evaluatorAsks.get() > 0,
                    "while a step that writes one asks it, so the count above is a live witness");
        }

        @Test
        void aHandInWhoseConditionDoesNotHoldIsNeitherOfferedNorCredited() {
            Quest quest = Quest.builder("q_report")
                    .objective(ObjectiveDef.builder("report", "TURN_IN").countsWhile(whileSworn()).build())
                    .build();
            QuestEngine engine = engine(quest);
            engine.accept(player, quest);

            assertFalse(engine.canDeliverTurnInAt(player, quest, "guide"));
            assertEquals(0, engine.tryTurnIn(player, quest, "report", "guide").credited());

            sworn.set(true);
            assertTrue(engine.canDeliverTurnInAt(player, quest, "guide"));
            assertEquals(1, engine.tryTurnIn(player, quest, "report", "guide").credited());
        }
    }

    // ==================== the achievement engine ====================

    @Nested
    class AchievementCriteria {

        @Test
        void aCriterionCountsOnlyWhileItsConditionHolds() {
            ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();
            kinds.register(null, ObjectiveKind.of("KILL_ENTITY"));
            AchievementEngine engine = AchievementEngine.builder()
                    .objectiveKinds(kinds)
                    .rewardKinds(new RewardKindRegistry())
                    .nativeEvents(false)
                    .build();
            Achievement achievement = Achievement.builder("sworn_slayer")
                    .criterion(kill("0", 5, whileSworn()))
                    .criterion(kill("1", 5, null))
                    .build();
            engine.setAchievements(List.of(achievement));

            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1L);
            assertEquals(0, engine.progressOf(player, achievement, 0).current());
            assertEquals(1, engine.progressOf(player, achievement, 1).current());

            sworn.set(true);
            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1L);
            assertEquals(1, engine.progressOf(player, achievement, 0).current());
            assertEquals(2, engine.progressOf(player, achievement, 1).current());
        }
    }

    // ==================== the audit ====================

    @Nested
    class Audit {

        private List<Finding> audit(String json) throws IOException {
            AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, "q", null);
            QuestDefinition definition = QuestAsset.CODEC.decodeAndInheritJsonAsset(
                    RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data)).toDefinition(null);
            return QuestPoolValidator.validate(new QuestPool(Map.of("q", definition)), null, null, null,
                    new GateKindRegistry());
        }

        @Test
        void aConditionNamingAFactorNothingAnswersOrThatCanNeverHoldIsReportedAtItsStep() throws Exception {
            List<Finding> unknown = audit("""
                    { "Objectives": { "defeat": { "Kind": "KILL_ENTITY",
                        "While": { "Factors": [ { "Factor": "yourmod:nothing_answers", "Min": 1 } ] } } } }
                    """);
            Finding named = unknown.stream().filter(f -> f.code().equals("UNKNOWN_FACTOR")).findFirst().orElse(null);
            assertNotNull(named, "the shared gate audit's code: " + unknown);
            assertTrue(named.message().startsWith("step 'defeat' While: "), named.message());

            List<Finding> never = audit("""
                    { "Objectives": { "defeat": { "Kind": "KILL_ENTITY", "While": { "Not": [ {} ] } } } }
                    """);
            assertTrue(never.stream().anyMatch(f -> f.code().equals("BLANK_REQUIREMENT")
                    && f.message().startsWith("step 'defeat' While: ")), never.toString());

            List<Finding> known = audit("""
                    { "Objectives": { "defeat": { "Kind": "KILL_ENTITY",
                        "While": { "Factors": [ { "Factor": "yourmod:sworn", "Min": 1 } ] } } } }
                    """);
            assertTrue(known.stream().noneMatch(f -> f.message().contains("While")), known.toString());
        }
    }

    @Test
    void anUnwrittenClauseIsNoCondition() {
        GateSpec blank = GateSpec.of(null, null, null, null, new GateClause[0], null, null);
        assertNull(kill("k", 1, blank).countsWhile());
        assertTrue(ObjectiveWhile.holds(null, kill("k", 1, null)), "nothing to ask, nothing refused");
        assertFalse(ObjectiveWhile.holds(null, kill("k", 1, whileSworn())), "a condition with nobody to ask refuses");
    }
}
