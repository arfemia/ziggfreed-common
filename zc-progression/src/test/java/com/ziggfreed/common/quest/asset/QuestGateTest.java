package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decode;
import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateKind;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * What a quest's {@code Requires} block decodes to, and what it answers.
 *
 * <p>The rule under test everywhere here is FAIL CLOSED: a requirement nothing can evaluate keeps
 * the quest locked. It is the difference between "this content needs that mod" and "the first
 * server without that mod hands the content out to everybody".
 */
class QuestGateTest {

    private static final Subject PLAYER =
            Subject.of(UUID.nameUUIDFromBytes("player".getBytes()), "Player");

    private FactorRegistry factors;
    private GateKindRegistry gateKinds;

    @BeforeEach
    void freshRegistries() {
        factors = new FactorRegistry("test");
        factors.derivedSource(null);
        gateKinds = new GateKindRegistry();
    }

    private GateEvaluator evaluator() {
        return GateEvaluator.builder().factors(factors).gateKinds(gateKinds).build();
    }

    // ==================== the codec ====================

    @Nested
    class TheCodec {

        @Test
        void everyLeafAndBothCombinatorsDecode() throws Exception {
            GateSpec requires = decodeRoot("""
                    { "Requires": {
                        "Factors": [ { "Factor": "yourmod:trade_rank", "Param": "smithing", "Min": 10 } ],
                        "Permission": "yourmod.quest.advanced",
                        "Quests": [ "intro_1", "intro_2" ],
                        "Custom": { "yourmod:reputation": { "Faction": "miners", "Min": "500" } },
                        "AllOf": [ { "Permission": "yourmod.beta" } ],
                        "AnyOf": [ { "Quests": ["route_a"] }, { "Quests": ["route_b"] } ],
                        "Not": [ { "Quests": ["retired_route"] } ] } }
                    """, "q").toDefinition(null).requires();

            assertEquals(1, requires.factorsOrEmpty().length);
            assertEquals("yourmod:trade_rank", requires.factorsOrEmpty()[0].getFactor());
            assertEquals("smithing", requires.factorsOrEmpty()[0].getParam());
            assertEquals(10.0, requires.factorsOrEmpty()[0].getMin());
            assertEquals("yourmod.quest.advanced", requires.getPermission());
            assertEquals(List.of("intro_1", "intro_2"), List.of(requires.questsOrEmpty()));
            assertEquals(Map.of("Faction", "miners", "Min", "500"),
                    requires.customOrEmpty().get("yourmod:reputation"));
            assertEquals(1, requires.allOfOrEmpty().length);
            assertEquals(2, requires.anyOfOrEmpty().length);
            assertEquals(1, requires.notOrEmpty().length);
            assertEquals(List.of("retired_route"), List.of(requires.notOrEmpty()[0].questsOrEmpty()));
            assertFalse(requires.isEmpty());
        }

        @Test
        void theJavaFactoryBuildsTheSameBlockTheCodecDoes() throws Exception {
            GateSpec decoded = decodeRoot("""
                    { "Requires": {
                        "Factors": [ { "Factor": "yourmod:rank", "Min": 10 } ],
                        "Permission": "yourmod.beta",
                        "Quests": [ "intro_1" ],
                        "AnyOf": [ { "Quests": ["route_a"] } ],
                        "Not": [ { "Quests": ["retired"] } ] } }
                    """, "q").toDefinition(null).requires();

            GateSpec built = GateSpec.of(
                    new FactorCondition[] {FactorCondition.of("yourmod:rank", null, 10.0, null)},
                    "yourmod.beta",
                    new String[] {"intro_1"},
                    null,
                    null,
                    new GateClause[] {GateClause.of(null, null, new String[] {"route_a"}, null)},
                    new GateClause[] {GateClause.of(null, null, new String[] {"retired"}, null)});

            assertEquals(decoded.factorsOrEmpty()[0].getFactor(), built.factorsOrEmpty()[0].getFactor());
            assertEquals(decoded.factorsOrEmpty()[0].getMin(), built.factorsOrEmpty()[0].getMin());
            assertEquals(decoded.getPermission(), built.getPermission());
            assertEquals(List.of(decoded.questsOrEmpty()), List.of(built.questsOrEmpty()));
            assertEquals(List.of(decoded.anyOfOrEmpty()[0].questsOrEmpty()),
                    List.of(built.anyOfOrEmpty()[0].questsOrEmpty()));
            assertEquals(List.of(decoded.notOrEmpty()[0].questsOrEmpty()),
                    List.of(built.notOrEmpty()[0].questsOrEmpty()));
            assertEquals(decoded.isEmpty(), built.isEmpty());
        }

        @Test
        void aChildMayAddARequirementWithoutLosingTheParentsOwn() throws Exception {
            QuestAsset parent = decodeRoot("""
                    { "Requires": { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] } }
                    """, "base");

            GateSpec requires = decode("""
                    { "Requires": { "Permission": "yourmod.beta" } }
                    """, "child", "base", parent).toDefinition(null).requires();

            assertEquals("yourmod.beta", requires.getPermission());
            assertEquals(1, requires.factorsOrEmpty().length,
                    "the inherited bound survives a child that only adds a permission");
        }

        @Test
        void anUnauthoredBlockAsksForNothing() throws Exception {
            assertTrue(decodeRoot("{ }", "q").toDefinition(null).requires().isEmpty());
        }
    }

    // ==================== evaluation ====================

    @Nested
    class Evaluation {

        @Test
        void aFactorBoundIsMetOrItIsNot() {
            factors.register("yourmod:rank", ctx -> 7.0);

            assertTrue(evaluator().passes(PLAYER, spec("""
                    { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] }
                    """)));
            assertEquals(GateEvaluator.REASON_FACTOR + "yourmod:rank",
                    evaluator().firstFailure(PLAYER, spec("""
                            { "Factors": [ { "Factor": "yourmod:rank", "Min": 10 } ] }
                            """)),
                    "the reason names the factor that shut the gate, so a consumer can explain it");
        }

        @Test
        void aFactorNobodyProvidesKeepsTheQuestLocked() {
            assertFalse(evaluator().passes(PLAYER, spec("""
                    { "Factors": [ { "Factor": "yourmod:absent" } ] }
                    """)), "content gated on a mod that is not installed must stay gated");
        }

        @Test
        void withNoFactorRegistryAtAllEveryBoundStillRefuses() {
            GateEvaluator unwired = GateEvaluator.builder().build();
            assertFalse(unwired.passes(PLAYER, spec("""
                    { "Factors": [ { "Factor": "yourmod:rank", "Min": 1 } ] }
                    """)));
        }

        /**
         * The whole point of desugaring the leaf: the two ways a server can write one permission
         * requirement have to mean one thing. They are one code path now rather than two that agree,
         * and this is what says so - held, not held, and unanswerable, all three the same verdict
         * whichever spelling asked.
         */
        @Test
        void thePermissionLeafAndTheSameFactorConditionGiveOneVerdict() {
            registerPermissionFactor("yourmod.beta");

            for (String node : List.of("yourmod.beta", "yourmod.alpha", "")) {
                boolean asLeaf = evaluator().passes(PLAYER, spec("{ \"Permission\": \"" + node + "\" }"));
                boolean asFactor = evaluator().passes(PLAYER, spec("{ \"Factors\": [ { \"Factor\": \""
                        + GateEvaluator.PERMISSION_FACTOR + "\", \"Param\": \"" + node
                        + "\", \"Min\": 1 } ] }"));
                assertEquals(asFactor, asLeaf,
                        "the leaf IS the factor, so both spellings answer alike for node '" + node + "'");
            }

            assertTrue(evaluator().passes(PLAYER, spec("{ \"Permission\": \"yourmod.beta\" }")),
                    "a node the player holds opens the gate");
        }

        @Test
        void aPermissionRefusalNamesTheLeafTheAuthorWrote() {
            registerPermissionFactor("yourmod.beta");

            assertEquals(GateEvaluator.REASON_PERMISSION,
                    evaluator().firstFailure(PLAYER, spec("{ \"Permission\": \"yourmod.alpha\" }")),
                    "the token names what is in the file, not the factor underneath it");
        }

        @Test
        void aPermissionNobodyCanAnswerKeepsTheQuestLocked() {
            assertEquals(GateEvaluator.REASON_PERMISSION,
                    evaluator().firstFailure(PLAYER, spec("{ \"Permission\": \"yourmod.beta\" }")),
                    "nothing registered the permission factor, so the reading fails closed");

            GateEvaluator noVocabulary = GateEvaluator.builder().build();
            assertFalse(noVocabulary.passes(PLAYER, spec("{ \"Permission\": \"yourmod.beta\" }")),
                    "and an evaluator with no vocabulary at all refuses it too");
        }

        @Test
        void aBlankPermissionNodeRefusesRatherThanAskingForNothing() {
            registerPermissionFactor("yourmod.beta");

            assertEquals(GateEvaluator.REASON_PERMISSION,
                    evaluator().firstFailure(PLAYER, spec("{ \"Permission\": \"\" }")),
                    "an empty node is not a node anybody holds, so the reading cannot be taken");
            assertEquals(GateEvaluator.REASON_PERMISSION,
                    evaluator().firstFailure(PLAYER, spec("{ \"Permission\": \"   \" }")));
        }

        /**
         * A stand-in for the portable {@code hytale:permission} provider, which reads a live
         * player and so cannot run here. It answers the same three ways the real one does: held,
         * not held, and nothing to read.
         */
        private void registerPermissionFactor(String heldNode) {
            factors.register(GateEvaluator.PERMISSION_FACTOR, ctx -> {
                String node = ctx.param() == null ? "" : ctx.param().trim();
                if (node.isEmpty()) {
                    return null;
                }
                return heldNode.equals(node) ? 1.0 : 0.0;
            });
        }

        @Test
        void aPrerequisiteIsAnsweredFromTheEnginesOwnRecords() {
            InMemoryQuestProgressStore store = new InMemoryQuestProgressStore();
            QuestEngine engine = QuestEngine.builder().store(store).nativeEvents(false).build();
            GateEvaluator evaluator = evaluator();
            AssetQuestGates gates = AssetQuestGates.of(evaluator);
            gates.useEngine(engine);

            assertEquals(GateEvaluator.REASON_QUEST + "intro_1",
                    evaluator.firstFailure(PLAYER, spec("{ \"Quests\": [\"intro_1\"] }")));

            store.setStatus(PLAYER, "intro_1", QuestStatus.COMPLETED);
            assertNull(evaluator.firstFailure(PLAYER, spec("{ \"Quests\": [\"intro_1\"] }")));
        }

        @Test
        void aRegisteredKindMayDesugarToOrdinaryFactorBounds() {
            factors.register("yourmod:trade_rank", ctx -> "smithing".equals(ctx.param()) ? 12.0 : 0.0);
            gateKinds.register("yourmod:trade", GateKind.desugaring(params -> {
                List<FactorCondition> conditions = new ArrayList<>();
                params.forEach((trade, min) -> conditions.add(
                        FactorCondition.of("yourmod:trade_rank", trade, Double.valueOf(min), null)));
                return conditions;
            }));

            assertTrue(evaluator().passes(PLAYER, spec("""
                    { "Custom": { "yourmod:trade": { "smithing": "10" } } }
                    """)), "a desugared gate behaves exactly like the same bound written by hand");
            assertFalse(evaluator().passes(PLAYER, spec("""
                    { "Custom": { "yourmod:trade": { "smithing": "20" } } }
                    """)));
        }

        @Test
        void aRegisteredKindMayAnswerTheQuestionItself() {
            gateKinds.register("yourmod:been_there",
                    GateKind.evaluating((subject, params) -> "north".equals(params.get("Camp"))));

            assertTrue(evaluator().passes(PLAYER,
                    spec("{ \"Custom\": { \"yourmod:been_there\": { \"Camp\": \"north\" } } }")));
            assertFalse(evaluator().passes(PLAYER,
                    spec("{ \"Custom\": { \"yourmod:been_there\": { \"Camp\": \"south\" } } }")));
        }

        @Test
        void anUnregisteredKindRefusesAndAThrowingOneDoesToo() {
            assertEquals(GateEvaluator.REASON_CUSTOM + "yourmod:absent",
                    evaluator().firstFailure(PLAYER, spec("""
                            { "Custom": { "yourmod:absent": { } } }
                            """)));

            gateKinds.register("yourmod:broken", (subject, params, support) -> {
                throw new IllegalStateException("boom");
            });
            assertEquals(GateEvaluator.REASON_CUSTOM + "yourmod:broken",
                    evaluator().firstFailure(PLAYER, spec("""
                            { "Custom": { "yourmod:broken": { } } }
                            """)),
                    "a gate that blows up must never open");
        }

        @Test
        void allOfMustAllPassAndAnyOfNeedsOnlyOne() {
            factors.register("yourmod:rank", ctx -> 7.0);

            assertTrue(evaluator().passes(PLAYER, spec("""
                    { "AnyOf": [ { "Factors": [ { "Factor": "yourmod:rank", "Min": 100 } ] },
                                 { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] } ] }
                    """)));
            assertEquals(GateEvaluator.REASON_ANY_OF, evaluator().firstFailure(PLAYER, spec("""
                    { "AnyOf": [ { "Factors": [ { "Factor": "yourmod:rank", "Min": 100 } ] },
                                 { "Factors": [ { "Factor": "yourmod:rank", "Min": 50 } ] } ] }
                    """)));
            assertFalse(evaluator().passes(PLAYER, spec("""
                    { "AllOf": [ { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] },
                                 { "Factors": [ { "Factor": "yourmod:rank", "Min": 100 } ] } ] }
                    """)));
        }

        @Test
        void aNotGroupShutsTheGateByPassing() {
            factors.register("yourmod:rank", ctx -> 7.0);

            // The group fails (rank 7 is not 100), so the negation holds and the gate is open.
            assertTrue(evaluator().passes(PLAYER, spec("""
                    { "Not": [ { "Factors": [ { "Factor": "yourmod:rank", "Min": 100 } ] } ] }
                    """)));
            // The group passes, which is exactly what a Not forbids.
            assertEquals(GateEvaluator.REASON_NOT, evaluator().firstFailure(PLAYER, spec("""
                    { "Not": [ { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] } ] }
                    """)));
        }

        @Test
        void oneNotGroupMeansNotBothWhileTwoMeanNeither() {
            factors.register("yourmod:rank", ctx -> 7.0);
            factors.register("yourmod:fame", ctx -> 1.0);

            // ONE group listing both: it only passes when BOTH hold, and fame is 1, so it fails
            // and the negation lets the player through.
            assertTrue(evaluator().passes(PLAYER, spec("""
                    { "Not": [ { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 },
                                              { "Factor": "yourmod:fame", "Min": 50 } ] } ] }
                    """)));
            // TWO groups: each is negated on its own, and the rank one passes, so the gate shuts.
            assertEquals(GateEvaluator.REASON_NOT, evaluator().firstFailure(PLAYER, spec("""
                    { "Not": [ { "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] },
                               { "Factors": [ { "Factor": "yourmod:fame", "Min": 50 } ] } ] }
                    """)));
        }

        @Test
        void anEmptyNotGroupShutsTheContentForEverybody() {
            // A group asking for nothing passes for everyone, so negating it can never be satisfied.
            assertEquals(GateEvaluator.REASON_NOT, evaluator().firstFailure(PLAYER, spec("""
                    { "Not": [ { } ] }
                    """)));
        }

        private GateSpec spec(String requiresJson) {
            try {
                return decodeRoot("{ \"Requires\": " + requiresJson + " }", "q").toDefinition(null).requires();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ==================== the engine bridge ====================

    @Nested
    class TheBridge {

        @Test
        void theEngineRefusesAnAcceptTheContentGated() throws Exception {
            factors.register("yourmod:rank", ctx -> 1.0);
            QuestPool pool = new QuestPool(Map.of("gated", decodeRoot("""
                    { "Requires": { "Factors": [ { "Factor": "yourmod:rank", "Min": 10 } ] },
                      "Visibility": { "RequirePrerequisites": true },
                      "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                    """, "gated").toDefinition(null)));

            AssetQuestGates gates = AssetQuestGates.of(evaluator());
            gates.pool(pool);
            QuestEngine engine = QuestEngine.builder().gates(gates).nativeEvents(false).build();
            engine.setQuests(pool.quests());

            Quest quest = engine.quest("gated");
            assertNotNull(quest);
            QuestEngine.AcceptCheck check = engine.canAccept(PLAYER, quest);
            assertFalse(check.allowed());
            assertTrue(check.reasons().contains(GateEvaluator.REASON_FACTOR + "yourmod:rank"),
                    "the content's own refusal rides alongside the engine's, so a consumer can name the "
                            + "factor rather than saying 'prerequisites': " + check.reasons());
            assertFalse(engine.isVisible(PLAYER, quest),
                    "a quest whose Visibility asks for prerequisites is hidden until they are met, and the "
                            + "gate that hides it is the same one that refuses the accept");
        }

        @Test
        void aQuestWithNoRequirementsNeedsNoWiringAtAll() throws Exception {
            QuestPool pool = new QuestPool(Map.of("open", decodeRoot("""
                    { "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                    """, "open").toDefinition(null)));

            AssetQuestGates gates = AssetQuestGates.of(GateEvaluator.builder().build());
            gates.pool(pool);
            QuestEngine engine = QuestEngine.builder().gates(gates).nativeEvents(false).build();
            engine.setQuests(pool.quests());

            assertTrue(engine.canAccept(PLAYER, engine.quest("open")).allowed());
        }

        @Test
        void aQuestTheGatesHaveNoPoolEntryForIsNotGated() throws Exception {
            AssetQuestGates gates = AssetQuestGates.of(evaluator());
            QuestEngine engine = QuestEngine.builder().gates(gates).nativeEvents(false).build();
            Quest quest = decodeRoot("{ }", "unknown").toDefinition(null).quest();
            engine.setQuests(List.of(quest));

            assertTrue(engine.canAccept(PLAYER, quest).allowed(),
                    "gates answer for the content they were given; a quest from somewhere else is not "
                            + "silently locked");
        }
    }
}
