package com.ziggfreed.common.progress.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.validation.Finding;

/**
 * The load checks a {@code Requires} block gets from what its factors' Factors files declare and from what a
 * registered kind reads: a Param outside its dataset, a bound its definite absent reading meets, a Max-only
 * bound on a membership factor, a kind parameter nobody reads.
 */
class GateValidatorTraitsTest {

    private static final String HELD = "yourmod:held";
    private static final String DONE = "yourmod:done";
    private static final String RANK = "yourmod:rank";
    private static final String RANKS = "yourmod:ranks";

    @AfterEach
    void tearDown() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of());
        EditorDataSets.serveForTests(RANKS, null);
        RefusalLines.resetForTests();
    }

    private static List<String> codes(FactorCondition... conditions) {
        GateSpec block = GateSpec.of(conditions, null, null, null, null, null, null);
        return GateValidator.validate(block, "test", "the_file", "thing", null, null, null).stream()
                .map(Finding::code).toList();
    }

    @Test
    void aConditionItsAbsentReadingMeetsGatesNothing() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of("held_traits",
                DerivedFactorAsset.of("held_traits", null, HELD, null, null, null, null, 0.0, null)));

        assertEquals(List.of("ABSENT_READING_PASSES"), codes(FactorCondition.of(HELD, "Sword", null, null)),
                "no bound at all passes on the absent 0");
        assertEquals(List.of("ABSENT_READING_PASSES"), codes(FactorCondition.of(HELD, "Sword", 0.0, null)),
                "a Min the absent reading meets is no gate either");
        assertEquals(List.of(), codes(FactorCondition.of(HELD, "Sword", 1.0, null)));
        assertEquals(List.of(), codes(FactorCondition.of(HELD, "Sword", null, 0.0)),
                "a Max is the deliberate 'not this' form");
    }

    @Test
    void aMaxOnlyBoundOnAMembershipFactorIsNamed() {
        RefusalLines.memberFactor(DONE, "yourmod.lock.done");

        assertEquals(List.of("MAX_ONLY_MEMBERSHIP"), codes(FactorCondition.of(DONE, "intro", null, 0.0)));
        assertEquals(List.of("MAX_ONLY_MEMBERSHIP"),
                codes(FactorCondition.of(GateEvaluator.PERMISSION_FACTOR, "yourmod.vip", null, 0.0)),
                "a permission refusal reads as asking for it too");
        assertEquals(List.of(), codes(FactorCondition.of(DONE, "intro", 1.0, null)));
    }

    @Test
    void aParamOutsideItsDataSetIsNamedAndAPatternIsLeftAlone() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of("rank_traits",
                DerivedFactorAsset.of("rank_traits", null, RANK, null, null, null, RANKS, null, null)));
        EditorDataSets.serveForTests(RANKS, () -> List.of("Veteran", "Elite"));

        assertEquals(List.of(), codes(FactorCondition.of(RANK, "veteran", 1.0, null)), "matched without case");
        assertEquals(List.of("UNKNOWN_FACTOR_PARAM"), codes(FactorCondition.of(RANK, "Novice", 1.0, null)));
        assertEquals(List.of(), codes(FactorCondition.of(RANK, "Vet*", 1.0, null)), "a pattern is not a value");
    }

    @Test
    void aKindParameterNobodyReadsIsNamed() {
        GateKindRegistry kinds = new GateKindRegistry();
        kinds.register("yourmod:trade", GateKind.evaluating((s, p) -> true).reading("Trade", "Min"));
        GateSpec block = GateSpec.of(null, null, null,
                Map.of("yourmod:trade", Map.of("trade", "smithing", "Mni", "10")), null, null, null);

        List<Finding> findings = GateValidator.validate(block, "test", "the_file", "thing", kinds, null, null);

        assertEquals(1, findings.size(), "the declared name matches without case; the misspelling does not");
        assertEquals("UNKNOWN_GATE_PARAM", findings.get(0).code());
        assertTrue(findings.get(0).message().contains("Mni"));
    }
}
