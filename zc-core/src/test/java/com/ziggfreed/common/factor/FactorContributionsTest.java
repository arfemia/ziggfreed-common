package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The cross-mod contribution door, and the one behaviour that matters more than the door itself:
 * <b>a server without the contributing mod behaves exactly as it did before the id existed</b>.
 *
 * <p>That is what lets one authored file be correct on both servers. A gate on an uncontributed id
 * stays shut (whatever its bounds say, including the bounds-less presence form) and a formula term
 * on one contributes zero, so an optional mod's bonus is a bonus rather than a load-bearing input.
 */
class FactorContributionsTest {

    private static final String RARITY = "mmomobscaling:mob_rarity_tier";

    private static FactorContext ctx() {
        return FactorContext.builder().build();
    }

    @BeforeEach
    @AfterEach
    void resetContributions() {
        FactorContributions.clearForTests();
    }

    // ==================== the mod is not installed ====================

    @Test
    void anUncontributedIdResolvesToNothingInAnyVocabulary() {
        FactorRegistry registry = new FactorRegistry("consumer");

        assertNull(registry.resolve(RARITY, ctx()));
        assertFalse(registry.isRegistered(RARITY));
        assertFalse(registry.ids().contains(RARITY));
    }

    @Test
    void aGateOnAnUncontributedIdStaysShutIncludingTheBoundsLessForm() {
        FactorRegistry registry = new FactorRegistry("consumer");

        FactorCondition atLeastElite = FactorCondition.of(RARITY, null, 2.0, null);
        FactorCondition presenceCheck = FactorCondition.of(RARITY, null, null, null);

        assertFalse(atLeastElite.accepts(registry.resolve(RARITY, ctx())));
        assertFalse(presenceCheck.accepts(registry.resolve(RARITY, ctx())),
                "'only where that mod is installed' is written as a bounds-less condition, so it "
                        + "must not spring open when the mod is missing");
    }

    @Test
    void aFormulaTermOnAnUncontributedIdContributesZeroAndTheRestSurvives() {
        FactorRegistry registry = new FactorRegistry("consumer");
        registry.register("yourmod:base_bonus", "consumer", c -> 4.0);

        FactorFormula formula = FactorFormula.of(2.0, new FactorFormula.Term[]{
                FactorFormula.Term.of("yourmod:base_bonus", null, 1.0),
                FactorFormula.Term.of(RARITY, null, 10.0),
        }, null);

        assertEquals(6.0, formula.evaluate(registry, ctx()),
                "the missing mod's term adds nothing; the base and every resolvable term stay");
    }

    // ==================== the mod is installed ====================

    @Test
    void aContributedIdAnswersInAVocabularyThatNeverRegisteredIt() {
        FactorContributions.register(RARITY, "mmomobscaling", c -> 3.0);
        FactorRegistry registry = new FactorRegistry("consumer");

        assertEquals(3.0, registry.resolve(RARITY, ctx()));
        assertTrue(registry.isRegistered(RARITY),
                "a validator must not report a cross-mod factor whose owner IS installed as unknown");
        assertTrue(registry.ids().contains(RARITY));
    }

    @Test
    void aContributedIdIsReadableByEveryVocabularyIncludingOnesBuiltBeforeIt() {
        FactorRegistry builtFirst = new FactorRegistry("consumer");
        FactorContributions.register(RARITY, "mmomobscaling", c -> 1.0);
        FactorRegistry builtAfter = new FactorRegistry("other");

        assertEquals(1.0, builtFirst.resolve(RARITY, ctx()),
                "setup order between two mods must not decide whether a factor resolves");
        assertEquals(1.0, builtAfter.resolve(RARITY, ctx()));
    }

    @Test
    void aTermAndAGateReadTheContributedValueOnceItIsThere() {
        FactorContributions.register(RARITY, "mmomobscaling", c -> 3.0);
        FactorRegistry registry = new FactorRegistry("consumer");

        FactorFormula formula = FactorFormula.of(20.0, new FactorFormula.Term[]{
                FactorFormula.Term.of(RARITY, null, 2.0),
        }, null);

        assertEquals(26.0, formula.evaluate(registry, ctx()));
        assertTrue(FactorCondition.of(RARITY, null, 2.0, null).accepts(registry.resolve(RARITY, ctx())));
    }

    @Test
    void aLocalRegistrationWinsOverAContributionOfTheSameId() {
        // A consumer answering a shared id in its own context (a session snapshot rather than the
        // live read) keeps doing so: its own vocabulary is consulted first.
        FactorContributions.register(RARITY, "mmomobscaling", c -> 3.0);
        FactorRegistry registry = new FactorRegistry("consumer");
        registry.register(RARITY, "consumer", c -> 9.0);

        assertEquals(9.0, registry.resolve(RARITY, ctx()));
    }

    @Test
    void theContributionIsOwnerTaggedAndListedPerContributor() {
        FactorContributions.register(RARITY, "mmomobscaling", c -> 3.0);
        FactorContributions.register("mmomobscaling:region_power", "mmomobscaling", c -> 12.0);
        FactorContributions.register("yourmod:season", "yourmod", c -> 1.0);

        Map<String, List<String>> contributors = FactorContributions.contributors();
        assertEquals(List.of("mmomobscaling:mob_rarity_tier", "mmomobscaling:region_power"),
                contributors.get("mmomobscaling"),
                "the boot diagnostic answers 'which mod do I need for this id'");
        assertEquals(List.of("yourmod:season"), contributors.get("yourmod"));
        assertEquals("mmomobscaling", FactorContributions.info().get(RARITY).owner());
    }

    @Test
    void reContributingTheSameProviderKeepsTheEntryAndItsHistory() {
        FactorProvider provider = c -> 3.0;
        FactorContributions.register(RARITY, "mmomobscaling", provider);
        FactorContributions.register(RARITY, "mmomobscaling", provider);

        assertEquals(1, FactorContributions.ids().size(),
                "a mod re-running its own setup must cost nothing");
        assertEquals("mmomobscaling", FactorContributions.info().get(RARITY).owner());
    }

    @Test
    void aBlankIdOrNullProviderIsIgnored() {
        FactorContributions.register("   ", "mmomobscaling", c -> 3.0);
        FactorContributions.register(RARITY, "mmomobscaling", null);

        assertTrue(FactorContributions.ids().isEmpty());
    }

    @Test
    void aThrowingContributedProviderFailsClosedAndCountsAgainstItsContributor() {
        FactorContributions.register(RARITY, "mmomobscaling", c -> {
            throw new IllegalStateException("provider blew up");
        });
        FactorRegistry registry = new FactorRegistry("consumer");

        assertNull(registry.resolve(RARITY, ctx()));
        assertEquals(1, FactorContributions.info().get(RARITY).failures(),
                "a broken contribution is countable against the mod that made it, not against the "
                        + "vocabulary that happened to ask");
        assertTrue(registry.info().get(RARITY).failures() > 0,
                "and an admin listing read through any registry sees the same history");
    }

    @Test
    void aRegistrysOwnClearLeavesContributionsAlone() {
        FactorContributions.register(RARITY, "mmomobscaling", c -> 3.0);
        FactorRegistry registry = new FactorRegistry("consumer");
        registry.register("yourmod:own", "consumer", c -> 1.0);

        registry.clear();

        assertNull(registry.resolve("yourmod:own", ctx()));
        assertEquals(3.0, registry.resolve(RARITY, ctx()),
                "contributions belong to the mods that made them, not to one vocabulary");
    }
}
