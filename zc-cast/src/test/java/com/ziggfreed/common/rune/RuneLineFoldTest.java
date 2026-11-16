package com.ziggfreed.common.rune;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;

/**
 * {@link RuneLineFold}'s fold over plain values: the engine's own {@code ImpactModifiers.collect} order and arithmetic,
 * since every rune number a consumer's cast takes comes out of it. A multiplicative step multiplies and an additive one
 * adds, in the order written and from support to support left to right, an attribute the core lacks starting from 1;
 * the rightmost conversion wins; a support that does not fit the core changes nothing.
 */
class RuneLineFoldTest {

    private static final float EPS = 1e-5f;

    private static RuneLineFold.Step mul(float amount) {
        return new RuneLineFold.Step(CalculationType.MULTIPLICATIVE, amount);
    }

    private static RuneLineFold.Step add(float amount) {
        return new RuneLineFold.Step(CalculationType.ADDITIVE, amount);
    }

    private static RuneLineFold.Support fits(Map<String, List<RuneLineFold.Step>> modifiers) {
        return new RuneLineFold.Support(true, modifiers, null, null);
    }

    @Test
    void aMultiplicativeStepMultipliesTheCoresValueAndAnUndeclaredOneStartsFromOne() {
        RuneLineFold.Folded folded = RuneLineFold.foldValues(Map.of("AoeScale", 2f),
                List.of(fits(Map.of("AoeScale", List.of(mul(1.6f)), "Cost", List.of(mul(1.5f))))));
        assertEquals(3.2f, folded.attributes().get("AoeScale"), EPS);
        assertEquals(1.5f, folded.attributes().get("Cost"), EPS, "an attribute the core lacks folds from 1");
    }

    @Test
    void anAdditiveStepAdds() {
        RuneLineFold.Folded folded = RuneLineFold.foldValues(Map.of("Fork", 1f),
                List.of(fits(Map.of("Fork", List.of(add(2f))))));
        assertEquals(3f, folded.attributes().get("Fork"), EPS);
    }

    @Test
    void stepsApplyInOrderWithinASupportAndAcrossTheLineLeftToRight() {
        Map<String, List<RuneLineFold.Step>> addThenMultiply = new LinkedHashMap<>();
        addThenMultiply.put("Damage", List.of(add(1f), mul(3f)));
        RuneLineFold.Folded within = RuneLineFold.foldValues(null, List.of(fits(addThenMultiply)));
        assertEquals(6f, within.attributes().get("Damage"), EPS, "(1 + 1) x 3, never 1 x 3 + 1");

        RuneLineFold.Folded across = RuneLineFold.foldValues(null, List.of(
                fits(Map.of("Damage", List.of(mul(3f)))),
                fits(Map.of("Damage", List.of(add(1f))))));
        assertEquals(4f, across.attributes().get("Damage"), EPS, "the left support first: 1 x 3 + 1");
    }

    @Test
    void theRightmostConversionWinsAndOnHitEffectsGatherInLineOrder() {
        RuneLineFold.Folded folded = RuneLineFold.foldValues(null, List.of(
                new RuneLineFold.Support(true, Map.of(), new String[] { "Rend" }, "Wind"),
                new RuneLineFold.Support(true, Map.of(), new String[] { "Wither" }, "Lightning")));
        assertEquals("Lightning", folded.elementalCauseId());
        assertArrayEquals(new String[] { "Rend", "Wither" }, folded.onHitEffectIds());
        assertNull(folded.attributes(), "nothing modified, no attributes carried");
    }

    @Test
    void aSupportThatDoesNotFitTheCoreChangesNothing() {
        RuneLineFold.Folded folded = RuneLineFold.foldValues(Map.of("Damage", 1f), List.of(
                new RuneLineFold.Support(false, Map.of("Damage", List.of(mul(5f))), new String[] { "Rend" }, "Wind"),
                fits(Map.of("Damage", List.of(mul(0.8f))))));
        assertEquals(0.8f, folded.attributes().get("Damage"), EPS);
        assertNull(folded.onHitEffectIds(), "the idle support's on-hit effect is not carried");
        assertNull(folded.elementalCauseId(), "nor its conversion");
    }

    @Test
    void aLineCarryingNothingFoldsToNothing() {
        assertNull(RuneLineFold.foldValues(null, List.of()));
        assertNull(RuneLineFold.foldValues(Map.of(), List.of(
                new RuneLineFold.Support(false, Map.of("Damage", List.of(mul(2f))), null, "Wind"))));
    }
}
