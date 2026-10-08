package com.ziggfreed.common.achievement.asset;

import static com.ziggfreed.common.achievement.asset.AchievementAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FeatureFlags;

/**
 * An achievement lifts a top-level plain {@code hytale:mod_installed} condition onto its hide axis, as a
 * quest does: the condition leaves the gate, and the achievement is out of circulation while the mod
 * cannot be answered present. A feature condition stays a gate, a refusal its self-heal re-reads. The
 * feature namespace is unique to this class.
 */
class AchievementModLiftTest {

    private static final String CRITERIA = "\"Criteria\": { \"a\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } }";

    @AfterEach
    void forget() {
        FeatureFlags.reset();
    }

    @Test
    void aModPresenceConditionLeavesTheGateAndHidesTheAchievement() throws Exception {
        AchievementDefinition definition = decodeRoot("{ \"Requires\": { \"Factors\": ["
                + " { \"Factor\": \"hytale:mod_installed\", \"Param\": \"Ziggfreed:KweebecNightmare\", \"Min\": 1 },"
                + " { \"Factor\": \"yourmod:rank\", \"Min\": 5 } ] }, " + CRITERIA + " }", "night_owl_t1").toDefinition();

        assertEquals(1, definition.requires().factorsOrEmpty().length, "only the rank stays a lock");
        assertEquals("yourmod:rank", definition.requires().factorsOrEmpty()[0].getFactor());
        assertFalse(definition.achievement().available(),
                "a unit JVM has no plugin table, and a presence that cannot be told hides, as a quest's does");
    }

    @Test
    void theBoundsLessFormIsLiftedToo() throws Exception {
        AchievementDefinition definition = decodeRoot("{ \"Requires\": { \"Factors\": ["
                + " { \"Factor\": \"hytale:mod_installed\", \"Param\": \"Ziggfreed:KweebecNightmare\" } ] }, "
                + CRITERIA + " }", "night_owl_t2").toDefinition();

        assertTrue(definition.requires().isEmpty(), "the fold-time drop leaves this form, and the lift takes it");
        assertFalse(definition.achievement().available());
    }

    @Test
    void aFeatureConditionStaysAGateOnAnAchievement() throws Exception {
        FeatureFlags.register("modlift_ach", "trading", "yourmod", () -> false);

        AchievementDefinition definition = decodeRoot("{ \"Requires\": { \"Factors\": ["
                + " { \"Factor\": \"modlift_ach:feature\", \"Param\": \"Trading\", \"Min\": 1 } ] }, "
                + CRITERIA + " }", "merchant").toDefinition();

        assertEquals("modlift_ach:feature", definition.requires().factorsOrEmpty()[0].getFactor(),
                "a feature gate is a refusal the self-heal re-reads, never a hide on an achievement");
        assertTrue(definition.achievement().available());
    }

    @Test
    void anAchievementGatingOnNoModIsUntouched() throws Exception {
        AchievementDefinition definition = decodeRoot("{ \"Requires\": { \"Factors\": ["
                + " { \"Factor\": \"yourmod:rank\", \"Min\": 5 } ] }, " + CRITERIA + " }", "plain").toDefinition();

        assertEquals(1, definition.requires().factorsOrEmpty().length);
        assertTrue(definition.achievement().available());
    }

    @Test
    void aYearlyCopyAndsTheLiftIntoItsYearsAvailability() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("modlift_fair", 2026, 2026).live("modlift_fair", 2026);
        AchievementAsset keeper = decodeRoot("{ \"Occurrence\": { \"Event\": \"modlift_fair\" },"
                + " \"Requires\": { \"Factors\": ["
                + " { \"Factor\": \"hytale:mod_installed\", \"Param\": \"Ziggfreed:KweebecNightmare\", \"Min\": 1 } ] }, "
                + CRITERIA + " }", "fair_keeper");

        AchievementDefinition copy = keeper.toDefinition(new OccurrenceMinting.Mint("fair_keeper_2026",
                "modlift_fair", 2026, "fair_keeper", calendar.reader(), UnaryOperator.identity()));

        assertTrue(copy.requires().isEmpty(), "the copy's gate loses the presence condition too");
        assertFalse(copy.achievement().available(),
                "its year runs, and the mod it waits on still cannot be told present, so the copy hides");
    }
}
