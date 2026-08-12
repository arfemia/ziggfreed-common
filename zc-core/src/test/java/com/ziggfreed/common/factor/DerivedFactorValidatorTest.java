package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.factor.FactorFormula.Clamp;
import com.ziggfreed.common.factor.FactorFormula.Term;

/**
 * The audit that makes an asset-defined factor's silent failures visible at load. Every case here
 * produces NO runtime error at all: the id simply answers nothing, or answers its base forever, and
 * whoever authored the content that leans on it goes looking for a missing NPC instead.
 */
class DerivedFactorValidatorTest {

    private static FactorFormula formula(String factor) {
        return FactorFormula.of(null, new Term[]{Term.of(factor, null, null)}, null);
    }

    private static boolean has(List<Finding> issues, String code) {
        return issues.stream().anyMatch(i -> i.code().equals(code));
    }

    private static Finding find(List<Finding> issues, String code) {
        return issues.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("expected a " + code + " finding, got " + issues));
    }

    @Test
    void aDefinitionOverARegisteredFactorIsClean() {
        List<Finding> issues = DerivedFactorValidator.validateAll(
                Map.of("yourmod:derived", formula("yourmod:quality")),
                Set.of("yourmod:quality")::contains);

        assertTrue(issues.isEmpty(), () -> "expected no findings, got " + issues);
    }

    @Test
    void aDefinitionOverAnotherDefinitionIsCleanWithNoRegistryKnowledgeAtAll() {
        List<Finding> issues = DerivedFactorValidator.validateAll(Map.of(
                "yourmod:top", formula("yourmod:mid"),
                "yourmod:mid", FactorFormula.of(1.0, null, null)));

        assertTrue(issues.isEmpty(), () -> "expected no findings, got " + issues);
    }

    @Test
    void anEmptyFormulaIsReportedAndNothingElseIsCheckedOnIt() {
        List<Finding> issues = DerivedFactorValidator.validateAll(Map.of("yourmod:empty", new FactorFormula()));

        assertEquals(1, issues.size());
        assertEquals("EMPTY_FORMULA", issues.get(0).code());
        assertEquals(Severity.ERROR, issues.get(0).severity());
        assertEquals("yourmod:empty", issues.get(0).sourceId());
    }

    @Test
    void aSelfReferenceIsAnError() {
        List<Finding> issues = DerivedFactorValidator.validateAll(
                Map.of("yourmod:self", formula("yourmod:self")));

        assertEquals(Severity.ERROR, find(issues, "SELF_REFERENCE").severity());
        assertFalse(has(issues, "CYCLE"),
                "a direct self-reference has its own clearer finding; reporting both would be noise");
    }

    @Test
    void anUnknownTermIdIsOnlyAWarningBecauseItsOwnerMayRegisterLater() {
        List<Finding> issues = DerivedFactorValidator.validateAll(
                Map.of("yourmod:derived", formula("absentmod:bonus")));

        Finding issue = find(issues, "UNKNOWN_FACTOR");
        assertEquals(Severity.WARNING, issue.severity(),
                "'this bonus applies only where that mod is installed' is the value side working, "
                        + "not a broken file");
        assertTrue(issue.message().contains("absentmod:bonus"));
    }

    @Test
    void aTermTheRegistryPredicateKnowsIsNotReported() {
        assertFalse(has(DerivedFactorValidator.validateAll(
                Map.of("yourmod:derived", formula("yourmod:live")),
                Set.of("yourmod:live")::contains), "UNKNOWN_FACTOR"));
    }

    @Test
    void aCycleAcrossSeveralDefinitionsIsWalkedStatically() {
        List<Finding> issues = DerivedFactorValidator.validateAll(Map.of(
                "yourmod:a", formula("yourmod:b"),
                "yourmod:b", formula("yourmod:c"),
                "yourmod:c", formula("yourmod:a")));

        assertEquals(3, issues.stream().filter(i -> i.code().equals("CYCLE")).count(),
                "every file in the loop gets its own finding, because any of the three is where an "
                        + "author would be looking");
        assertTrue(find(issues, "CYCLE").message().contains("yourmod:a"));
    }

    @Test
    void anAcyclicDiamondIsNotMistakenForACycle() {
        List<Finding> issues = DerivedFactorValidator.validateAll(Map.of(
                "yourmod:top", FactorFormula.of(null, new Term[]{
                        Term.of("yourmod:left", null, null), Term.of("yourmod:right", null, null)}, null),
                "yourmod:left", formula("yourmod:leaf"),
                "yourmod:right", formula("yourmod:leaf"),
                "yourmod:leaf", FactorFormula.of(1.0, null, null)));

        assertFalse(has(issues, "CYCLE"),
                "two paths reaching one definition is reuse, not a loop");
    }

    @Test
    void aNonFiniteBaseOrWeightIsAnError() {
        assertEquals(Severity.ERROR, find(DerivedFactorValidator.validateAll(
                Map.of("yourmod:bad", FactorFormula.of(Double.NaN,
                        new Term[]{Term.of("yourmod:x", null, null)}, null)),
                Set.of("yourmod:x")::contains), "NON_FINITE").severity());

        assertTrue(has(DerivedFactorValidator.validateAll(
                Map.of("yourmod:bad", FactorFormula.of(1.0,
                        new Term[]{Term.of("yourmod:x", null, Double.POSITIVE_INFINITY)}, null)),
                Set.of("yourmod:x")::contains), "NON_FINITE"));
    }

    @Test
    void aBlankTermIsReportedOnceHoweverManyThereAre() {
        List<Finding> issues = DerivedFactorValidator.validateAll(
                Map.of("yourmod:derived", FactorFormula.of(1.0, new Term[]{
                        Term.of(null, null, null), Term.of("  ", null, null)}, null)));

        assertEquals(1, issues.stream().filter(i -> i.code().equals("BLANK_TERM")).count());
        assertEquals(Severity.WARNING, find(issues, "BLANK_TERM").severity());
    }

    @Test
    void anInvertedOrNonFiniteClampIsAnError() {
        assertTrue(has(DerivedFactorValidator.validateAll(
                Map.of("yourmod:derived", FactorFormula.of(1.0, null, Clamp.of(5.0, 1.0)))),
                "CLAMP_INVERTED"));
        assertTrue(has(DerivedFactorValidator.validateAll(
                Map.of("yourmod:derived", FactorFormula.of(1.0, null, Clamp.of(Double.NaN, 1.0)))),
                "NON_FINITE"));
    }

    @Test
    void idsAreMatchedTheWayTheRegistryMatchesThem() {
        List<Finding> issues = DerivedFactorValidator.validateAll(
                Map.of("YourMod:Derived", formula("YOURMOD:DERIVED")));

        assertTrue(has(issues, "SELF_REFERENCE"),
                "casing must never hide a self-reference, since the registry folds it away");
    }
}
