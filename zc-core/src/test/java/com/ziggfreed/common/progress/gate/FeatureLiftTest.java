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
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.factor.ModFactors;

/**
 * {@link FeatureLift} lifts the plain top-level feature and mod-presence conditions off a
 * {@code Requires} block: the caller's own feature factor ({@code <namespace>:feature}) through
 * {@code lift}, every declared namespace's through {@code liftKnown}, and
 * {@link ModFactors#MOD_INSTALLED} (a companion mod's mere presence) either way. Both land in
 * {@link FeatureLift.Result#lifted} for the hide axis, so this suite pins the lift itself: which
 * conditions move, which stay, and how the {@code Param} case is handled on the way.
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

    // ==================== the lifted entry ====================

    @Test
    void theLiftedEntryKeepsTheParamAsAuthoredAndSpellsTheFlatIdTheWayTheTableIsRead() {
        GateSpec requires = requiresOf(
                FactorCondition.of(FEATURE_FACTOR, " Mastery ", null, null),
                FactorCondition.of(ModFactors.MOD_INSTALLED, "Ziggfreed:RpgStations", null, null));

        FeatureLift.Result result = FeatureLift.lift(requires, FEATURE_FACTOR, "test_quest", NO_WARN);

        assertEquals(2, result.lifted().size());
        FeatureLift.Lifted feature = result.lifted().get(0);
        assertEquals(FEATURE_FACTOR, feature.factorId());
        assertEquals("Mastery", feature.param(), "trimmed, case kept on the entry");
        assertEquals("mastery", feature.featureId(), "lower-cased on the flat list");
        assertTrue(!feature.isModPresence());
        FeatureLift.Lifted mod = result.lifted().get(1);
        assertTrue(mod.isModPresence());
        assertEquals("Ziggfreed:RpgStations", mod.featureId());
        assertEquals(List.of("mastery", "Ziggfreed:RpgStations"), result.features());
        assertTrue(FeatureLift.allOn(List.of()), "nothing lifted holds trivially");
    }

    // ==================== liftKnown: every declared namespace ====================

    @Test
    void liftKnownLiftsEveryDeclaredNamespacesFeatureFactorAndLeavesAnUndeclaredOneInTheGate() {
        FeatureFlags.register("liftknown_a", "trading", "yourmod", () -> true);
        FeatureFlags.register("liftknown_b", "fishing", "othermod", () -> false);
        try {
            GateSpec requires = requiresOf(
                    FactorCondition.of("liftknown_a:feature", "trading", 1.0, null),
                    FactorCondition.of("liftknown_b:feature", "fishing", null, null),
                    FactorCondition.of("nobody_declared_lift:feature", "x", 1.0, null),
                    FactorCondition.of(ModFactors.MOD_INSTALLED, "Some:Mod", 1.0, null),
                    FactorCondition.of("hytale:stat", "channel", 30.0, null));

            FeatureLift.Result result = FeatureLift.liftKnown(requires);

            assertEquals(List.of("trading", "fishing", "Some:Mod"), result.features(),
                    "both declared namespaces and the mod presence lift, in authored order");
            GateSpec remaining = result.requires();
            assertNotNull(remaining);
            assertEquals(2, remaining.factorsOrEmpty().length);
            assertEquals("nobody_declared_lift:feature", remaining.factorsOrEmpty()[0].getFactor(),
                    "an undeclared namespace may not be installed yet, so it stays a fail-closed lock");
            assertEquals("hytale:stat", remaining.factorsOrEmpty()[1].getFactor());

            assertTrue(result.lifted().get(0).isOn(), "read live through the declaring mod's supplier");
            assertTrue(!result.lifted().get(1).isOn());
            assertTrue(!FeatureLift.allOn(result.lifted()), "one off is off");
        } finally {
            FeatureFlags.reset();
        }
    }

    @Test
    void liftKnownOnAnEmptyOrAbsentBlockIsTheOpenResult() {
        assertEquals(FeatureLift.Result.OPEN, FeatureLift.liftKnown(null));
        assertEquals(FeatureLift.Result.OPEN, FeatureLift.liftKnown(new GateSpec()));
    }

    // ==================== fixtures ====================

    @Nonnull
    private static GateSpec requiresOf(@Nonnull FactorCondition... factors) {
        return GateSpec.of(factors, null, null, null, null, null, null);
    }
}
