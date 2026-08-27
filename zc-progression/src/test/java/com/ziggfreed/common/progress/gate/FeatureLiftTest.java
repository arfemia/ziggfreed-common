package com.ziggfreed.common.progress.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.ModFactors;

/**
 * {@link FeatureLift} lifts exactly two factor ids off the top level of a {@code Requires} block:
 * the caller's own feature factor ({@code <namespace>:feature}) and
 * {@link ModFactors#MOD_INSTALLED} (a companion mod's mere presence). Both land in
 * {@link FeatureLift.Result#features} for the caller's hide axis, so this suite pins the lift
 * itself: which conditions move, which stay, and how the {@code Param} case is handled on the way.
 */
class FeatureLiftTest {

    /** The feature factor a consuming mod would hand in - any {@code <namespace>:feature} id. */
    private static final String FEATURE_FACTOR = "yourmod:feature";

    private static final Consumer<String> NO_WARN = warning -> { };

    // ==================== the caller's feature factor ====================

    @Test
    void aBoundsLessFeatureConditionIsLiftedAndLowercased() {
        GateSpec requires = requiresOf(FactorCondition.of(FEATURE_FACTOR, "Mastery", null, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertEquals(List.of("mastery"), result.features(),
                "a bounds-less feature condition is the presence check FeatureLift exists for");
        assertNull(result.requires(), "nothing else was authored, so the whole block is spent");
    }

    @Test
    void aFeatureConditionWithMinOneIsLiftedTheSameAsBoundsLess() {
        GateSpec requires = requiresOf(FactorCondition.of(FEATURE_FACTOR, "mastery", 1.0, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertEquals(List.of("mastery"), result.features());
    }

    @Test
    void aFeatureConditionWithAnUpperBoundStaysAGate() {
        GateSpec requires = requiresOf(FactorCondition.of(FEATURE_FACTOR, "mastery", null, 0.0));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertTrue(result.features().isEmpty(), "'only while OFF' is a real requirement, not a hide");
        GateSpec remaining = result.requires();
        assertNotNull(remaining, "the condition must stay in the gate");
        assertEquals(1, remaining.factorsOrEmpty().length);
    }

    @Test
    void anotherNamespacesFeatureFactorIsNotLifted() {
        GateSpec requires = requiresOf(FactorCondition.of("othermod:feature", "mastery", null, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertTrue(result.features().isEmpty(),
                "only the caller's OWN feature factor is a hide condition to this consumer");
        assertNotNull(result.requires());
    }

    // ==================== hytale:mod_installed ====================

    @Test
    void aBoundsLessModInstalledConditionIsLifted() {
        GateSpec requires = requiresOf(
                FactorCondition.of(ModFactors.MOD_INSTALLED, "Ziggfreed:RpgStations", null, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertEquals(List.of("Ziggfreed:RpgStations"), result.features(),
                "a bounds-less mod-installed condition hides content the same way a feature does");
        assertNull(result.requires());
    }

    @Test
    void aModInstalledConditionWithMinOneIsLiftedTheSameAsBoundsLess() {
        GateSpec requires = requiresOf(
                FactorCondition.of(ModFactors.MOD_INSTALLED, "Ziggfreed:RpgStations", 1.0, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertEquals(List.of("Ziggfreed:RpgStations"), result.features());
    }

    @Test
    void aModInstalledConditionWithMaxZeroStaysAGate() {
        // "only where it is NOT installed" - a real requirement a player either meets or does not,
        // never a reason to hide the content outright.
        GateSpec requires = requiresOf(
                FactorCondition.of(ModFactors.MOD_INSTALLED, "Ziggfreed:RpgStations", null, 0.0));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertTrue(result.features().isEmpty());
        GateSpec remaining = result.requires();
        assertNotNull(remaining);
        assertEquals(ModFactors.MOD_INSTALLED, remaining.factorsOrEmpty()[0].getFactor());
    }

    @Test
    void theParamCaseIsPreservedForAModInstalledCondition() {
        // The engine's plugin table matches Group:Name case-sensitively, so lower-casing it here
        // (the way a plain feature id is lower-cased) would make a genuinely installed mod read
        // as absent downstream.
        GateSpec requires = requiresOf(
                FactorCondition.of(ModFactors.MOD_INSTALLED, "Ziggfreed:RpgStations", null, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertEquals("Ziggfreed:RpgStations", result.features().get(0),
                "mixed case must survive the lift untouched");
    }

    @Test
    void aFeatureIdAndAModIdBothLiftFromTheSameBlock() {
        GateSpec requires = requiresOf(
                FactorCondition.of(FEATURE_FACTOR, "mastery", null, null),
                FactorCondition.of(ModFactors.MOD_INSTALLED, "Ziggfreed:RpgStations", null, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertEquals(List.of("mastery", "Ziggfreed:RpgStations"), result.features(),
                "authored order is preserved across both lifted ids");
        assertNull(result.requires());
    }

    // ==================== unrelated factors are left alone ====================

    @Test
    void anUnrelatedFactorIsNeverLifted() {
        GateSpec requires = requiresOf(FactorCondition.of("hytale:stat", "MMO_Level_MINING", 30.0, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertTrue(result.features().isEmpty(), "only the two lifted ids ever move");
        GateSpec remaining = result.requires();
        assertNotNull(remaining);
        assertEquals(1, remaining.factorsOrEmpty().length);
        assertEquals("hytale:stat", remaining.factorsOrEmpty()[0].getFactor());
    }

    @Test
    void aNestedFactorInsideAnyOfIsNeverLifted() {
        // Only the TOP LEVEL is examined; FeatureLift never walks into AnyOf/AllOf/Not.
        GateClause inner = requiresOf(
                FactorCondition.of(ModFactors.MOD_INSTALLED, "Ziggfreed:RpgStations", null, null));
        GateSpec requires = GateSpec.of(null, null, null, null, null,
                new GateClause[] {inner}, null);

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertTrue(result.features().isEmpty(),
                "a route inside AnyOf is a genuine either-or, not a hide condition");
        assertNotNull(result.requires());
    }

    // ==================== fixtures ====================

    @Nonnull
    private static GateSpec requiresOf(@Nonnull FactorCondition... factors) {
        return GateSpec.of(factors, null, null, null, null, null, null);
    }
}
