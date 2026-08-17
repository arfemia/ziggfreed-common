package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code hytale:mod_installed} reading's PURE half: the {@code Group:Name} parse, and the fact
 * that {@link ModFactors#contribute()} claims the id for every vocabulary on the server.
 *
 * <p>The engine half - what the live plugin table actually contains - is deliberately not tested
 * here: {@code PluginManager.get()} answers null with no server anywhere near it, and standing one up
 * to assert "this mod is loaded" would test the engine rather than this class. What CAN go wrong
 * without a server is the parse, which is the whole of the authored surface, so that is what is
 * pinned rung by rung.
 */
class ModFactorsTest {

    @BeforeEach
    @AfterEach
    void resetContributions() {
        FactorContributions.clearForTests();
    }

    // ==================== the id ====================

    @Test
    void theIdIsNamespacedToTheEngineThatOwnsThePluginTable() {
        assertEquals("hytale:mod_installed", ModFactors.MOD_INSTALLED,
                "content is authored against this exact string, so it is part of the contract");
    }

    // ==================== the parse ====================

    @Test
    void aWellFormedParamYieldsBothHalves() {
        ModFactors.ModRef mod = ModFactors.parseModRef("Ziggfreed:RpgStations");

        assertNotNull(mod);
        assertEquals("Ziggfreed", mod.group());
        assertEquals("RpgStations", mod.name());
    }

    @Test
    void whitespaceAroundEitherHalfIsFormattingRatherThanPartOfTheName() {
        ModFactors.ModRef mod = ModFactors.parseModRef("  Ziggfreed : RpgStations  ");

        assertNotNull(mod);
        assertEquals("Ziggfreed", mod.group());
        assertEquals("RpgStations", mod.name());
    }

    @Test
    void aMissingParamNamesNoMod() {
        assertNull(ModFactors.parseModRef(null));
        assertNull(ModFactors.parseModRef(""));
        assertNull(ModFactors.parseModRef("   "));
    }

    @Test
    void aParamWithNoColonNamesNoMod() {
        assertNull(ModFactors.parseModRef("RpgStations"),
                "a bare name has no group, and guessing one would gate on the wrong mod");
    }

    @Test
    void aParamWithTwoColonsNamesNoMod() {
        assertNull(ModFactors.parseModRef("Ziggfreed:RpgStations:1.0.0"),
                "the engine's own identity is a pair; a third part is an authoring mistake, not a "
                        + "version to quietly discard");
    }

    @Test
    void anEmptyHalfNamesNoMod() {
        assertNull(ModFactors.parseModRef(":RpgStations"));
        assertNull(ModFactors.parseModRef("Ziggfreed:"));
        assertNull(ModFactors.parseModRef(":"));
        assertNull(ModFactors.parseModRef("  :  "));
    }

    // ==================== the contribution ====================

    @Test
    void contributeClaimsTheIdForEveryVocabularyOnTheServer() {
        ModFactors.contribute();

        assertTrue(FactorContributions.isContributed(ModFactors.MOD_INSTALLED));
        assertNotNull(FactorContributions.provider(ModFactors.MOD_INSTALLED));
        assertTrue(FactorContributions.ids().contains(ModFactors.MOD_INSTALLED));
        assertEquals(ModFactors.OWNER,
                FactorContributions.info().get(ModFactors.MOD_INSTALLED).owner(),
                "the claim is attributed to this library, so an admin listing names who to ask");
    }

    @Test
    void contributingTwiceIsSilentAndLeavesOneClaim() {
        ModFactors.contribute();
        ModFactors.contribute();

        assertEquals(1, FactorContributions.ids().size());
    }

    @Test
    void aContributedIdResolvesThroughARegistryThatNeverRegisteredIt() {
        ModFactors.contribute();
        FactorRegistry registry = new FactorRegistry("consumer");

        assertTrue(registry.isRegistered(ModFactors.MOD_INSTALLED));
        // No plugin table in a unit JVM, so the reading itself is unanswerable rather than a
        // definite 0 - which is exactly the "cannot tell" rung the provider promises.
        assertNull(registry.resolve(ModFactors.MOD_INSTALLED,
                FactorContext.builder().param("Ziggfreed:RpgStations").build()));
    }

    @Test
    void aMalformedParamIsUnanswerableRatherThanADefiniteNo() {
        assertNull(ModFactors.resolveModInstalled(FactorContext.builder().build()));
        assertNull(ModFactors.resolveModInstalled(
                FactorContext.builder().param("RpgStations").build()));
    }

    // ==================== the presence-check idiom ====================

    /**
     * The truth a bounds-less condition on this id gets wrong: {@code 0} (absent) is a non-null
     * finite value exactly like {@code 1} (installed), so {@link FactorCondition#accepts} - which
     * passes ANY non-null finite reading when neither bound is authored - cannot tell the two
     * apart. Authored content must use {@code Min: 1}, never the bounds-less shortcut, to gate on
     * this id actually being installed.
     */
    @Test
    void aBoundsLessConditionAcceptsBothPresentAndAbsent() {
        FactorCondition boundsLess = FactorCondition.of(ModFactors.MOD_INSTALLED,
                "Ziggfreed:RpgStations", null, null);

        assertTrue(boundsLess.accepts(1.0), "installed reads 1.0, a non-null finite value");
        assertTrue(boundsLess.accepts(0.0),
                "absent reads a definite 0.0, also non-null and finite - the bounds-less gate "
                        + "cannot distinguish it from installed");
    }

    @Test
    void minOneIsTheCorrectPresenceCheck() {
        FactorCondition requiresInstalled = FactorCondition.of(ModFactors.MOD_INSTALLED,
                "Ziggfreed:RpgStations", 1.0, null);

        assertTrue(requiresInstalled.accepts(1.0));
        assertFalse(requiresInstalled.accepts(0.0),
                "Min: 1 is what actually rejects the absent-mod 0.0 reading");
    }
}
