package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The portable {@code hytale:} vocabulary's contract in the one situation a unit JVM can honestly
 * exercise: <b>no live subject at all</b>. That is not a contrived case - a placement gate is
 * evaluated before anything stands there, and an asset validator runs with no player in hand - so
 * "answers null, never throws, never invents a zero" is the behaviour that decides whether a gate
 * authored on these ids is safe to evaluate anywhere.
 *
 * <p>The live-subject paths (a real hotbar, a real item asset) need a running server and are
 * covered by in-game smoke, matching the rest of this module's split.
 */
class HytaleFactorsTest {

    private static FactorContext ctx(String param) {
        return FactorContext.builder().param(param).build();
    }

    @Test
    void registerIntoClaimsEveryPortableIdUnderTheGivenOwner() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        assertEquals(List.of(
                        HytaleFactors.HELD_ITEM,
                        HytaleFactors.HELD_TAG,
                        HytaleFactors.STAT,
                        HytaleFactors.TOOL_DURABILITY_PERCENT,
                        HytaleFactors.TOOL_ITEM_LEVEL,
                        HytaleFactors.TOOL_POWER,
                        HytaleFactors.TOOL_QUALITY),
                registry.ids(),
                "the portable set is fixed - a new id here is a deliberate vocabulary addition");
        assertEquals("yourmod", registry.info().get(HytaleFactors.STAT).owner());
    }

    @Test
    void everyFactorAnswersNothingWithNoSubjectRatherThanZero() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        for (String id : registry.ids()) {
            assertNull(registry.resolve(id, ctx("anything")),
                    id + " must be unresolvable with no subject, so a gate on it stays shut");
        }
    }

    @Test
    void aGateOnAnyPortableFactorFailsClosedWithNoSubject() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        for (String id : registry.ids()) {
            assertEquals(id,
                    FactorConditions.firstFailure(
                            List.of(FactorCondition.of(id, "anything", null, null)), registry, ctx(null)),
                    id + " must fail even a bounds-less presence check when it cannot be read");
        }
    }

    @Test
    void aBlankParamIsNotEnoughForTheIdsThatAddressSomething() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        // stat / held_tag / held_item all need a Param to name what they are asking about; with
        // none they cannot answer, which must read the same as any other unanswerable factor.
        assertNull(registry.resolve(HytaleFactors.STAT, ctx(null)));
        assertNull(registry.resolve(HytaleFactors.HELD_TAG, ctx("   ")));
        assertNull(registry.resolve(HytaleFactors.HELD_ITEM, ctx(null)));
    }

    @Test
    void resolvingIsSideEffectFreeSoNoProviderIsEverCountedAsFailed() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        for (String id : registry.ids()) {
            registry.resolve(id, ctx("anything"));
        }

        registry.info().forEach((id, info) ->
                assertEquals(0, info.failures(), id + " answered null by DECIDING to, never by throwing"));
        assertTrue(registry.info().size() == registry.ids().size());
    }
}
