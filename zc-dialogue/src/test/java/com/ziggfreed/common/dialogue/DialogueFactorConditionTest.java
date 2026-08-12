package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * The generic {@code Factor} dialogue condition: a number some OTHER mod owns, gating a line.
 *
 * <p>Everything here is about the two ways it can be unanswerable, because both are silent at
 * runtime and both must HIDE the gated content rather than offer it: an id nobody registered, and
 * an engine that was never handed a registry at all. The second is the one that decides what a
 * server sees when the vocabulary's owning mod simply is not installed.
 */
class DialogueFactorConditionTest {

    /** The decode vocabulary is process-wide; start every test from a clean one. */
    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    private static DialogueEngine engine(FactorRegistry factors) {
        return DialogueEngine.builder().warn(m -> { }).factors(factors).build();
    }

    /** A dialogue whose single option is gated on {@code conditionJson}. */
    private static NpcDialogue gated(DialogueEngine engine, String conditionJson) {
        NpcDialogue d = engine.decode("f",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"gated\",\"Conditions\":[" + conditionJson + "],"
                        + "\"Actions\":[{\"Type\":\"Close\"}]}]}}}");
        assertNotNull(d);
        return d;
    }

    private static boolean optionOffered(DialogueEngine engine, NpcDialogue d) {
        DialogueOption option = d.getNode("g").getOptions().get(0);
        return engine.optionAvailable(d, "g", option, new TestDialogueContext(d));
    }

    // ==================== Decode ====================

    @Test
    void decodesToTheSharedConditionLeaf() {
        DialogueEngine engine = engine(new FactorRegistry());
        NpcDialogue d = gated(engine,
                "{\"Type\":\"Factor\",\"Factor\":\"yourmod:reputation\",\"Param\":\"guild\","
                        + "\"Min\":10,\"Max\":20}");

        DialogueCondition condition = d.getNode("g").getOptions().get(0).getConditions().get(0);
        assertTrue(condition instanceof DialogueCondition.Factor,
                "Type 'Factor' must decode to DialogueCondition.Factor");
        DialogueCondition.Factor factor = (DialogueCondition.Factor) condition;

        assertEquals("yourmod:reputation", factor.getFactor());
        assertEquals("guild", factor.getCondition().getParam());
        assertEquals(10.0, factor.getCondition().getMin());
        assertEquals(20.0, factor.getCondition().getMax());
    }

    // ==================== Evaluation ====================

    @Test
    void aRegisteredFactorInsideTheBoundsOffersTheOption() {
        FactorRegistry factors = new FactorRegistry();
        factors.register("yourmod:reputation", c -> "guild".equals(c.param()) ? 15.0 : 0.0);
        DialogueEngine engine = engine(factors);

        assertTrue(optionOffered(engine, gated(engine,
                "{\"Type\":\"Factor\",\"Factor\":\"yourmod:reputation\",\"Param\":\"guild\",\"Min\":10}")));
    }

    @Test
    void aRegisteredFactorOutsideTheBoundsHidesTheOption() {
        FactorRegistry factors = new FactorRegistry();
        factors.register("yourmod:reputation", c -> 5.0);
        DialogueEngine engine = engine(factors);

        assertFalse(optionOffered(engine, gated(engine,
                "{\"Type\":\"Factor\",\"Factor\":\"yourmod:reputation\",\"Min\":10}")));
    }

    @Test
    void aBoundLessConditionIsAPresenceCheckOnTheFactorItself() {
        FactorRegistry factors = new FactorRegistry();
        factors.register("yourmod:installed", c -> 0.0);
        DialogueEngine engine = engine(factors);

        assertTrue(optionOffered(engine, gated(engine,
                        "{\"Type\":\"Factor\",\"Factor\":\"yourmod:installed\"}")),
                "a resolvable factor passes a bounds-less check even when its value is zero");
        assertFalse(optionOffered(engine, gated(engine,
                        "{\"Type\":\"Factor\",\"Factor\":\"yourmod:absent\"}")),
                "an unregistered one fails the same check - that is the whole point of it");
    }

    @Test
    void aThrowingProviderHidesTheOptionRatherThanBreakingTheRender() {
        FactorRegistry factors = new FactorRegistry();
        factors.register("yourmod:boom", c -> {
            throw new IllegalStateException("provider blew up");
        });
        DialogueEngine engine = engine(factors);

        assertFalse(optionOffered(engine, gated(engine,
                "{\"Type\":\"Factor\",\"Factor\":\"yourmod:boom\",\"Min\":1}")));
    }

    @Test
    void anEngineWithNoRegistryWiredFailsEveryFactorConditionClosed() {
        DialogueEngine engine = engine(null);

        assertFalse(optionOffered(engine, gated(engine,
                        "{\"Type\":\"Factor\",\"Factor\":\"yourmod:reputation\"}")),
                "a server that never installed the vocabulary's owner must see the ungated"
                        + " conversation, never a line it cannot back up");
    }

    @Test
    void aFactorConditionNestedInACombinatorEvaluatesTheSameWay() {
        FactorRegistry factors = new FactorRegistry();
        factors.register("yourmod:reputation", c -> 15.0);
        DialogueEngine engine = engine(factors);

        assertTrue(optionOffered(engine, gated(engine,
                "{\"Type\":\"AnyOf\",\"Any\":[{\"Type\":\"Factor\",\"Factor\":\"yourmod:missing\"},"
                        + "{\"Type\":\"Factor\",\"Factor\":\"yourmod:reputation\",\"Min\":10}]}")));
    }

    // ==================== Validator ====================

    @Test
    void validatorFlagsAFactorNothingRegistered() {
        FactorRegistry factors = new FactorRegistry();
        factors.register("yourmod:reputation", c -> 1.0);
        DialogueEngine engine = engine(factors);
        NpcDialogue d = gated(engine,
                "{\"Type\":\"AllOf\",\"All\":[{\"Type\":\"Factor\",\"Factor\":\"yourmod:reputaton\"}]}");

        List<String> codes = DialogueStructureValidator.validateAll(List.of(d), null, factors)
                .stream().map(Finding::code).toList();

        assertTrue(codes.contains("FACTOR_CONDITION_UNKNOWN_FACTOR"), codes.toString());
    }

    @Test
    void validatorStaysSilentOnAKnownFactorAndWithNoRegistryToCompareAgainst() {
        FactorRegistry factors = new FactorRegistry();
        factors.register("yourmod:reputation", c -> 1.0);
        DialogueEngine engine = engine(factors);
        NpcDialogue known = gated(engine,
                "{\"Type\":\"Factor\",\"Factor\":\"YourMod:Reputation\",\"Min\":1}");

        assertFalse(DialogueStructureValidator.validate(known, null, factors).stream()
                        .anyMatch(i -> i.code().equals("FACTOR_CONDITION_UNKNOWN_FACTOR")),
                "ids match case-insensitively, so casing alone is never a finding");

        NpcDialogue unknown = gated(engine, "{\"Type\":\"Factor\",\"Factor\":\"yourmod:absent\"}");
        assertFalse(DialogueStructureValidator.validate(unknown).stream()
                        .anyMatch(i -> i.code().equals("FACTOR_CONDITION_UNKNOWN_FACTOR")),
                "no registry passed means cannot tell, exactly like an empty selector pool");
    }

    @Test
    void validatorFlagsAFactorConditionWithNoIdAtAll() {
        FactorRegistry factors = new FactorRegistry();
        DialogueEngine engine = engine(factors);
        NpcDialogue d = gated(engine, "{\"Type\":\"Factor\",\"Min\":1}");

        List<String> codes = DialogueStructureValidator.validate(d, null, factors)
                .stream().map(Finding::code).toList();

        assertTrue(codes.contains("FACTOR_CONDITION_NO_ID"), codes.toString());
    }
}
