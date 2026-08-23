package com.ziggfreed.common.achievement;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.asset.AchievementDefinition;
import com.ziggfreed.common.progress.asset.ContentListingAsset.ChainMembership;
import com.ziggfreed.common.progress.gate.GateSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing facts on the runtime {@link Achievement}: the builder defaults, the normalization,
 * the {@link Achievement#withAuthoring} / {@link Achievement#withListing} copies carrying every
 * leaf, and the folded {@link AchievementDefinition} stamping its listing onto the engine object -
 * the seam a shared browsing surface groups, sorts and collapses a merged catalogue by.
 */
class AchievementListingTest {

    @Test
    void builderDefaultsCarryNoListing() {
        Achievement achievement = Achievement.builder("plain").build();
        assertNull(achievement.category());
        assertNull(achievement.subcategory());
        assertEquals(0, achievement.sortOrder());
        assertTrue(achievement.chains().isEmpty());
        assertNull(achievement.primaryChain());
        assertFalse(achievement.featOfStrength());
        assertNull(achievement.legacySince());
    }

    @Test
    void builderNormalizesAndOrdersListing() {
        Achievement achievement = Achievement.builder("listed")
                .category("  Combat ")
                .subcategory(" Bosses ")
                .sortOrder(40)
                .chains(List.of(ChainMembership.of("slayer", 2), ChainMembership.of("hunter", 1)))
                .featOfStrength(true)
                .legacySince(" 1.4.0 ")
                .build();
        assertEquals("combat", achievement.category());
        assertEquals("bosses", achievement.subcategory());
        assertEquals(40, achievement.sortOrder());
        assertEquals(2, achievement.chains().size());
        assertEquals("slayer", achievement.primaryChain().getId());
        assertEquals(2, achievement.primaryChain().tierOrZero());
        assertTrue(achievement.featOfStrength());
        assertEquals("1.4.0", achievement.legacySince());
    }

    @Test
    void builderBlankLegacySinceIsNull() {
        Achievement achievement = Achievement.builder("blank-legacy")
                .legacySince("   ")
                .build();
        assertNull(achievement.legacySince());
    }

    @Test
    void copiesCarryEveryListingLeaf() {
        Achievement listed = Achievement.builder("carried")
                .category("gathering")
                .subcategory("ore")
                .sortOrder(7)
                .chains(List.of(ChainMembership.of("prospecting", 3)))
                .featOfStrength(true)
                .legacySince("1.4.0")
                .build();

        Achievement reauthored = listed.withAuthoring(null, null, true, "Copper_Ore");
        assertEquals("gathering", reauthored.category());
        assertEquals("ore", reauthored.subcategory());
        assertEquals(7, reauthored.sortOrder());
        assertEquals("prospecting", reauthored.primaryChain().getId());
        assertTrue(reauthored.featOfStrength());
        assertEquals("1.4.0", reauthored.legacySince());
        assertTrue(reauthored.serverFirst());
        assertEquals("Copper_Ore", reauthored.icon());

        Achievement relisted = reauthored.withListing("combat", null, 12, List.of());
        assertEquals("combat", relisted.category());
        assertNull(relisted.subcategory());
        assertEquals(12, relisted.sortOrder());
        assertTrue(relisted.chains().isEmpty());
        // The non-listing facts carry over untouched.
        assertTrue(relisted.featOfStrength());
        assertEquals("1.4.0", relisted.legacySince());
        assertTrue(relisted.serverFirst());
        assertEquals("Copper_Ore", relisted.icon());
    }

    @Test
    void foldedDefinitionStampsListingOntoTheEngineObject() {
        Achievement bare = Achievement.builder("stamped").build();
        AchievementDefinition definition = new AchievementDefinition("stamped", bare,
                null, null, "Stamped", List.of(), List.of(),
                "Combat", "bosses", 25,
                List.of(ChainMembership.of("slayer", 1)), "Iron_Sword",
                GateSpec.OPEN, Map.of(), Map.of());
        Achievement stamped = definition.achievement();
        assertEquals("combat", stamped.category());
        assertEquals("bosses", stamped.subcategory());
        assertEquals(25, stamped.sortOrder());
        assertEquals("slayer", stamped.primaryChain().getId());
        assertEquals("Iron_Sword", stamped.icon());
    }
}
