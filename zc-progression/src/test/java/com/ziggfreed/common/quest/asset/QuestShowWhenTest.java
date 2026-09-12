package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.RequiresGates;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.validation.Finding;

/**
 * {@code Listing.ShowWhen}: the third visibility knob, a requirement block of its own that has to
 * pass before the quest is shown anywhere, independently of the accept gate.
 *
 * <p>The three things worth pinning are the three ways it could quietly be something else: that it
 * hides and then shows as the player's standing changes (so it is LIVE, not folded once); that it
 * never touches accept (so it is not a second {@code Requires}); and that a player already holding
 * the quest sees it regardless (so it cannot strand a carried quest).
 */
class QuestShowWhenTest {

    private static final String RANK = "yourmod:rank";

    private static final String QUEST_JSON = """
            { "Listing": { "ShowWhen": { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] } },
              "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore", "Amount": 3 } } }
            """;

    private AtomicInteger rank;
    private QuestEngine engine;
    private Subject player;

    @BeforeEach
    void setUp() {
        rank = new AtomicInteger();
        FactorRegistry factors = new FactorRegistry("test");
        factors.register(RANK, "test", ctx -> Double.valueOf(rank.get()));
        GateEvaluator evaluator = GateEvaluator.builder().factors(factors).build();
        engine = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .gates(RequiresGates.of(evaluator))
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    private Quest publish(String json, String id) throws Exception {
        Quest quest = decodeRoot(json, id).toDefinition(null).quest();
        engine.setQuests(List.of(quest));
        return quest;
    }

    // ==================== the codec ====================

    @Nested
    class TheCodec {

        @Test
        void showWhenDecodesInTheSameShapeAsRequiresAndRidesTheVisibility() throws Exception {
            Quest quest = decodeRoot(QUEST_JSON, "hidden_until_ranked").toDefinition(null).quest();

            GateSpec showWhen = quest.visibility().showWhen();
            assertNotNull(showWhen);
            assertTrue(quest.visibility().hasShowWhen());
            assertEquals(RANK, showWhen.factorsOrEmpty()[0].getFactor());
            assertEquals(5.0, showWhen.factorsOrEmpty()[0].getMin());
            assertFalse(quest.visibility().hidden(), "the other two knobs are untouched by it");
            assertFalse(quest.visibility().requirePrerequisites());
            assertTrue(quest.requires() == null || quest.requires().isEmpty(),
                    "and the accept gate carries none of it");
        }

        @Test
        void theTwoKnobFactoryCarriesNoBlockAndAnEmptyBlockReadsAsNone() {
            assertNull(new Quest.Visibility(true, true).showWhen());
            assertFalse(new Quest.Visibility(true, true).hasShowWhen());
            assertNull(new Quest.Visibility(false, false, new GateSpec()).showWhen(),
                    "a block asking for nothing holds nothing back");
            assertEquals(Quest.Visibility.OPEN, new Quest.Visibility(false, false));
        }

        @Test
        void aChildInheritsItsParentsShowWhenUntilItAuthorsOne() throws Exception {
            QuestAsset parent = decodeRoot(QUEST_JSON, "base");
            Quest child = QuestAssetCodecTest.decode("{ \"Listing\": { \"SortOrder\": 3 } }", "child", "base", parent)
                    .toDefinition(null).quest();

            assertNotNull(child.visibility().showWhen(), "a leaf the child did not mention is inherited");
            assertEquals(3, child.listOrder());
        }
    }

    // ==================== the visibility rule ====================

    @Nested
    class TheRule {

        @Test
        void aQuestHidesUntilItsBlockPassesAndShowsTheMomentItDoes() throws Exception {
            Quest quest = publish(QUEST_JSON, "hidden_until_ranked");

            assertFalse(engine.isVisible(player, quest), "below the rank the block holds it back");
            assertFalse(engine.isOfferable(player, quest), "at its own giver too: it is 'not yet', not 'not here'");

            rank.set(5);
            assertTrue(engine.isVisible(player, quest), "read LIVE: the same object shows once the rank is met");
            assertTrue(engine.isOfferable(player, quest));
        }

        @Test
        void theAcceptGateIsRequiresAloneSoShowWhenNeverBlocksTaking() throws Exception {
            Quest quest = publish(QUEST_JSON, "hidden_until_ranked");

            assertTrue(engine.canAccept(player, quest).allowed(),
                    "ShowWhen decides whether the quest is shown, never whether it may be taken");
            assertTrue(engine.accept(player, quest));
        }

        @Test
        void aPlayerAlreadyHoldingTheQuestSeesItWhateverTheBlockSays() throws Exception {
            Quest quest = publish(QUEST_JSON, "hidden_until_ranked");
            rank.set(5);
            assertTrue(engine.accept(player, quest));

            rank.set(0);
            assertTrue(engine.isVisible(player, quest), "somebody must be able to see what they are in the middle of");
            assertTrue(engine.isOfferable(player, quest));
        }

        @Test
        void hiddenStillKeepsAPassingQuestOffOpenListingsButNotOffItsGiver() throws Exception {
            Quest quest = publish("""
                    { "Listing": { "Hidden": true,
                                   "ShowWhen": { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] } },
                      "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                    """, "surprise");
            rank.set(5);

            assertFalse(engine.isVisible(player, quest), "the three knobs are independent: Hidden still applies");
            assertTrue(engine.isOfferable(player, quest), "and a giver still hands it out once ShowWhen passes");
        }

        @Test
        void showWhenAndRequirePrerequisitesCompose() throws Exception {
            Quest quest = publish("""
                    { "Listing": { "RequirePrerequisites": true,
                                   "ShowWhen": { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] } },
                      "Requires": { "Factors": [ { "Factor": "yourmod:rank", "Min": 10 } ] },
                      "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                    """, "two_stage");

            rank.set(5);
            assertFalse(engine.isVisible(player, quest), "ShowWhen passes, but the accept gate is not yet met");
            rank.set(10);
            assertTrue(engine.isVisible(player, quest));
        }

        @Test
        void aGateWithNoReadingOfTheBlockLeavesTheQuestShown() throws Exception {
            QuestEngine bare = QuestEngine.builder()
                    .store(new InMemoryQuestProgressStore())
                    .nativeEvents(false)
                    .warn(message -> { })
                    .build();
            Quest quest = decodeRoot(QUEST_JSON, "hidden_until_ranked").toDefinition(null).quest();
            bare.setQuests(List.of(quest));

            assertTrue(bare.isVisible(player, quest),
                    "the default gate answers true, so the decision stays with the gate that evaluates blocks");
        }
    }

    // ==================== the audit ====================

    @Test
    void theValidatorAuditsShowWhenLikeRequiresAndSaysWhichBlock() throws Exception {
        QuestDefinition chained = decodeRoot("""
                { "Listing": { "ShowWhen": { "Quests": [ "nobody_authored_this" ] } },
                  "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                """, "chained").toDefinition(null);
        assertNotNull(chained.quest().visibility().showWhen());
        Map<String, QuestDefinition> definitions = new LinkedHashMap<>();
        definitions.put("chained", chained);

        List<Finding> findings = QuestPoolValidator.validate(new QuestPool(definitions), null, null, null, null);

        Finding unknown = findings.stream()
                .filter(f -> f.code().equals("SHOW_WHEN_UNKNOWN_PREREQUISITE")).findFirst().orElse(null);
        assertNotNull(unknown, "the same gate audit runs over the block: " + findings);
        assertTrue(unknown.message().startsWith("in Listing.ShowWhen:"),
                "and names the block, so the author looks in the right place: " + unknown.message());
        assertTrue(unknown.message().contains("nobody_authored_this"));
        assertEquals("chained", unknown.sourceId());
    }

    @Test
    void aBlockAuthoredInJavaReadsTheSameAsOneDecoded() {
        GateSpec showWhen = GateSpec.of(new FactorCondition[] {FactorCondition.of(RANK, null, 5.0, null)},
                null, null, null, null, null, null);
        Quest quest = Quest.builder("java_built")
                .visibility(new Quest.Visibility(false, false, showWhen))
                .build();
        engine.setQuests(List.of(quest));

        assertFalse(engine.isVisible(player, quest));
        rank.set(5);
        assertTrue(engine.isVisible(player, quest));
    }
}
