package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The shared factor vocabulary's ONE load-bearing behaviour: <b>a gate never silently opens</b>.
 *
 * <p>Every way a factor can fail to produce a number - nobody registered the id, the provider
 * cannot answer, the provider threw, the provider returned a NaN - has to be indistinguishable at
 * the gate, and has to FAIL rather than pass. The tempting alternative (a zero default) passes any
 * condition with no lower bound, which is the exact shape "only where that mod is installed" is
 * written in, so it would spring open precisely when the owning mod is missing.
 */
class FactorVocabularyTest {

    private static FactorContext ctx(String param) {
        return FactorContext.builder().param(param).build();
    }

    // ==================== registry: fail closed ====================

    @Test
    void anUnregisteredFactorResolvesToNothing() {
        FactorRegistry registry = new FactorRegistry();

        assertNull(registry.resolve("yourmod:feature", ctx(null)));
        assertFalse(registry.isRegistered("yourmod:feature"));
    }

    @Test
    void aBlankFactorIdResolvesToNothing() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:feature", c -> 1.0);

        assertNull(registry.resolve(null, ctx(null)));
        assertNull(registry.resolve("   ", ctx(null)));
    }

    @Test
    void aThrowingProviderIsCaughtCountedAndTreatedAsUnresolvable() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:boom", "modA", c -> {
            throw new IllegalStateException("provider blew up");
        });

        assertNull(registry.resolve("yourmod:boom", ctx(null)));
        assertEquals(1, registry.info().get("yourmod:boom").failures(),
                "a broken provider must be countable against its owner, not just swallowed");
        assertEquals("modA", registry.info().get("yourmod:boom").owner());
    }

    @Test
    void aProviderThatCannotAnswerAndANonFiniteOneBothResolveToNothing() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:quiet", c -> null);
        registry.register("yourmod:nan", c -> Double.NaN);
        registry.register("yourmod:infinite", c -> Double.POSITIVE_INFINITY);

        assertNull(registry.resolve("yourmod:quiet", ctx(null)));
        assertNull(registry.resolve("yourmod:nan", ctx(null)));
        assertNull(registry.resolve("yourmod:infinite", ctx(null)));
    }

    @Test
    void aRegisteredProviderSeesTheContextsOwnParam() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:feature", c -> "shop".equals(c.param()) ? 1.0 : 0.0);

        assertEquals(1.0, registry.resolve("yourmod:feature", ctx("shop")));
        assertEquals(0.0, registry.resolve("yourmod:feature", ctx("forge")));
    }

    @Test
    void registrationIsPerInstanceSoOneConsumersVocabularyNeverLeaksIntoAnothers() {
        FactorRegistry mine = new FactorRegistry();
        FactorRegistry theirs = new FactorRegistry();
        mine.register("yourmod:feature", c -> 1.0);

        assertTrue(mine.isRegistered("yourmod:feature"));
        assertFalse(theirs.isRegistered("yourmod:feature"));
        assertNull(theirs.resolve("yourmod:feature", ctx(null)));
    }

    @Test
    void idsAreMatchedCaseInsensitivelyAndListedNormalized() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("YourMod:Feature", c -> 7.0);

        assertEquals(7.0, registry.resolve("yourmod:FEATURE", ctx(null)));
        assertEquals(List.of("yourmod:feature"), registry.ids());
    }

    // ==================== condition: the accepts matrix ====================

    @Test
    void aNullResolutionAlwaysFailsWhateverTheBoundsSay() {
        assertFalse(FactorCondition.of("f", null, null, null).accepts(null),
                "the bounds-less presence check is the shape that must fail hardest on null");
        assertFalse(FactorCondition.of("f", null, 1.0, null).accepts(null));
        assertFalse(FactorCondition.of("f", null, null, 1.0).accepts(null));
        assertFalse(FactorCondition.of("f", null, 0.0, 100.0).accepts(null));
    }

    @Test
    void aNonFiniteResolutionFailsToo() {
        assertFalse(FactorCondition.of("f", null, null, null).accepts(Double.NaN));
        assertFalse(FactorCondition.of("f", null, null, null).accepts(Double.POSITIVE_INFINITY));
    }

    @Test
    void noBoundsPlusARealNumberPasses() {
        assertTrue(FactorCondition.of("f", null, null, null).accepts(0.0));
        assertTrue(FactorCondition.of("f", null, null, null).accepts(-5.0));
    }

    @Test
    void bothBoundsAreInclusiveAndIndependentlyOptional() {
        assertTrue(FactorCondition.of("f", null, 1.0, null).accepts(1.0));
        assertFalse(FactorCondition.of("f", null, 1.0, null).accepts(0.999));
        assertTrue(FactorCondition.of("f", null, null, 3.0).accepts(3.0));
        assertFalse(FactorCondition.of("f", null, null, 3.0).accepts(3.001));
        assertTrue(FactorCondition.of("f", null, 1.0, 3.0).accepts(2.0));
        assertFalse(FactorCondition.of("f", null, 1.0, 3.0).accepts(3.5));
    }

    @Test
    void isBlankReportsAnEntryThatCanNeverBeEvaluated() {
        assertTrue(FactorCondition.of(null, null, 1.0, null).isBlank());
        assertTrue(FactorCondition.of("  ", null, null, null).isBlank());
        assertFalse(FactorCondition.of("f", null, null, null).isBlank());
    }

    // ==================== the array evaluator ====================

    @Test
    void everyConditionMustPassAndTheFirstFailureIsTheOneReported() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:low", c -> 1.0);
        registry.register("yourmod:high", c -> 10.0);

        List<FactorCondition> conditions = List.of(
                FactorCondition.of("yourmod:high", null, 5.0, null),
                FactorCondition.of("yourmod:low", null, 5.0, null),
                FactorCondition.of("yourmod:missing", null, null, null));

        assertEquals("yourmod:low", FactorConditions.firstFailure(conditions, registry, ctx(null)));
        assertFalse(FactorConditions.pass(conditions, registry, ctx(null)));
    }

    @Test
    void allFailuresReportsEveryUnmetConditionAndSkipsThePassingOnes() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:low", c -> 1.0);
        registry.register("yourmod:high", c -> 10.0);

        List<FactorCondition> conditions = List.of(
                FactorCondition.of("yourmod:high", null, 5.0, null),
                FactorCondition.of("yourmod:low", null, 5.0, null),
                FactorCondition.of(null, null, 5.0, null),
                FactorCondition.of("yourmod:missing", null, null, null));

        List<FactorCondition> failed = FactorConditions.allFailures(conditions, registry, ctx(null));

        assertEquals(2, failed.size());
        assertEquals("yourmod:low", failed.get(0).getFactor());
        assertEquals("yourmod:missing", failed.get(1).getFactor());
    }

    @Test
    void allFailuresIsEmptyWhenNothingFailedAndWhenThereWasNothingToEvaluate() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:high", c -> 10.0);

        FactorCondition[] conditions = {FactorCondition.of("yourmod:high", null, 5.0, null)};

        assertTrue(FactorConditions.allFailures(conditions, registry, ctx(null)).isEmpty());
        assertTrue(FactorConditions.allFailures((List<FactorCondition>) null, registry, ctx(null)).isEmpty());
        assertTrue(FactorConditions.allFailures(List.of(), registry, ctx(null)).isEmpty());
    }

    @Test
    void allFailuresKeepsTwoBoundsOnOneFactorApartByTheirOwnParam() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:stat", c -> "mining".equals(c.param()) ? 1.0 : 2.0);

        List<FactorCondition> conditions = List.of(
                FactorCondition.of("yourmod:stat", "mining", 5.0, null),
                FactorCondition.of("yourmod:stat", "combat", 5.0, null));

        List<FactorCondition> failed = FactorConditions.allFailures(conditions, registry, ctx(null));

        assertEquals(2, failed.size());
        assertEquals("mining", failed.get(0).getParam());
        assertEquals("combat", failed.get(1).getParam());
    }

    @Test
    void anEmptyOrAbsentArrayPassesVacuously() {
        FactorRegistry registry = new FactorRegistry();

        assertNull(FactorConditions.firstFailure((List<FactorCondition>) null, registry, ctx(null)));
        assertNull(FactorConditions.firstFailure(List.of(), registry, ctx(null)));
        assertTrue(FactorConditions.pass(new FactorCondition[0], registry, ctx(null)));
    }

    @Test
    void aBlankEntryIsSkippedRatherThanFailingTheWholeArray() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:ok", c -> 1.0);

        FactorCondition[] conditions = {
                FactorCondition.of(null, null, 1.0, null),
                FactorCondition.of("yourmod:ok", null, 1.0, null)};

        assertNull(FactorConditions.firstFailure(conditions, registry, ctx(null)));
    }

    @Test
    void eachEntryIsResolvedWithItsOwnParamNotTheOuterContexts() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:named", c -> "shop".equals(c.param()) ? 1.0 : 0.0);

        List<FactorCondition> conditions = List.of(
                FactorCondition.of("yourmod:named", "shop", 1.0, null),
                FactorCondition.of("yourmod:named", "forge", 1.0, null));

        assertEquals("yourmod:named", FactorConditions.firstFailure(conditions, registry, ctx("shop")),
                "two entries on one factor must address it independently through their own Param");
    }

    @Test
    void theOuterContextsWorldStoreAndPayloadSurviveIntoEveryEntry() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:here", c -> "hub".equals(c.payload(String.class)) ? 1.0 : 0.0);

        List<FactorCondition> conditions = List.of(FactorCondition.of("yourmod:here", null, 1.0, null));

        assertTrue(FactorConditions.pass(conditions, registry,
                FactorContext.builder().payload("hub").build()));
        assertFalse(FactorConditions.pass(conditions, registry,
                FactorContext.builder().payload("elsewhere").build()));
    }

    // ==================== context ====================

    @Test
    void anUnsetContextLeafReadsNullAndAPayloadNarrowsByType() {
        FactorContext empty = FactorContext.builder().build();

        assertNull(empty.param());
        assertNull(empty.world());
        assertNull(empty.store());
        assertNull(empty.subject());
        assertNull(empty.payload());
        assertFalse(empty.hasLiveSubject());

        FactorContext withPayload = FactorContext.builder().payload("hub").build();
        assertEquals("hub", withPayload.payload(String.class));
        assertNull(withPayload.payload(Integer.class));
    }
}
