package com.ziggfreed.common.match;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pins the three-route item-identity core: each route's own semantics, the null/empty ("route not
 * taken") contract, the tag presence form, and the {@link ItemMatch#any} OR-composition including
 * the no-route-authored answer. Pure fixtures throughout - the test authors every id, family and
 * tag map itself, no engine and no balance data.
 */
class ItemMatchTest {

    // ==================== itemId route ====================

    @Test
    void itemId_caseInsensitiveEquality() {
        assertTrue(ItemMatch.itemId("Wood_Oak_Trunk", "Wood_Oak_Trunk"));
        assertTrue(ItemMatch.itemId("wood_oak_trunk", "WOOD_OAK_TRUNK"));
        assertFalse(ItemMatch.itemId("Wood_Oak_Trunk", "Wood_Ash_Trunk"));
    }

    @Test
    void itemId_routeNotTakenIsFalse() {
        assertFalse(ItemMatch.itemId(null, "Wood_Oak_Trunk"));
        assertFalse(ItemMatch.itemId("  ", "Wood_Oak_Trunk"));
        assertFalse(ItemMatch.itemId("Wood_Oak_Trunk", null));
    }

    // ==================== resource-family route ====================

    @Test
    void resourceFamily_singleCandidate() {
        assertTrue(ItemMatch.resourceFamily("Rock", "rock"));
        assertFalse(ItemMatch.resourceFamily("Rock", "Wood"));
        assertFalse(ItemMatch.resourceFamily(null, "Rock"));
        assertFalse(ItemMatch.resourceFamily("", "Rock"));
        assertFalse(ItemMatch.resourceFamily("Rock", (String) null));
    }

    @Test
    void resourceFamily_anyOfTheCandidateSet() {
        String[] families = {"Wood_Oak_Trunk", "Fuel"};
        assertTrue(ItemMatch.resourceFamily("fuel", families));
        assertTrue(ItemMatch.resourceFamily("Wood_Oak_Trunk", families));
        assertFalse(ItemMatch.resourceFamily("Rock", families));
    }

    @Test
    void resourceFamily_setNullEmptyOrHoleyIsHandled() {
        assertFalse(ItemMatch.resourceFamily("Rock", (String[]) null));
        assertFalse(ItemMatch.resourceFamily("Rock", new String[0]));
        assertTrue(ItemMatch.resourceFamily("Rock", new String[] {null, "Rock"}));
    }

    // ==================== tags route ====================

    @Test
    void tags_familyAndValuesForm_anyOf() {
        Map<String, String[]> required = Map.of("Type", new String[] {"Rock", "Ore"});
        assertTrue(ItemMatch.tags(required, Map.of("Type", new String[] {"rock"})));
        assertTrue(ItemMatch.tags(required, Map.of("Type", new String[] {"Ore", "Metal"})));
        assertFalse(ItemMatch.tags(required, Map.of("Type", new String[] {"Wood"})));
        assertFalse(ItemMatch.tags(required, Map.of("Family", new String[] {"Rock"})));
    }

    @Test
    void tags_presenceForm_emptyValueArrayMatchesOnKeyAlone() {
        Map<String, String[]> required = Map.of("Planks", new String[0]);
        assertTrue(ItemMatch.tags(required, Map.of("planks", new String[0])),
                "presence form is case-insensitive on the key");
        assertTrue(ItemMatch.tags(required, Map.of("Planks", new String[] {"Oak"})),
                "a key carrying values still counts as present");
        assertFalse(ItemMatch.tags(required, Map.of("Type", new String[] {"Planks"})),
                "presence tests KEYS, not values under other keys - the engine's expanded raw-tag"
                        + " map already lifts every value to a key of its own");
    }

    @Test
    void tags_nonEmptyValuesNeverFallBackToPresence() {
        Map<String, String[]> required = Map.of("Type", new String[] {"Rock"});
        assertFalse(ItemMatch.tags(required, Map.of("Type", new String[0])),
                "a values-form family with no matching value is no match, even though the key exists");
    }

    @Test
    void tags_routeNotTakenIsFalse() {
        assertFalse(ItemMatch.tags(null, Map.of("Type", new String[] {"Rock"})));
        assertFalse(ItemMatch.tags(Map.of(), Map.of("Type", new String[] {"Rock"})));
        assertFalse(ItemMatch.tags(Map.of("Type", new String[] {"Rock"}), null));
        assertFalse(ItemMatch.tags(Map.of("Type", new String[] {"Rock"}), Map.of()));
    }

    // ==================== any (the OR-composition) ====================

    @Test
    void any_eachRouteAloneSatisfies() {
        assertTrue(ItemMatch.any("Rock_Stone", null, null,
                "rock_stone", null, null));
        assertTrue(ItemMatch.any(null, Map.of("Type", new String[] {"Rock"}), null,
                "Rock_Stone", Map.of("Type", new String[] {"Rock"}), null));
        assertTrue(ItemMatch.any(null, null, "Rock",
                "Rock_Stone", null, new String[] {"Rock"}));
    }

    @Test
    void any_severalRoutesAuthored_isSatisfiedByAnyOne() {
        // Exact id misses, the family route hits: the OR answers true regardless of route order.
        assertTrue(ItemMatch.any("Rock_Stone_Brick", null, "Rock",
                "Rock_Stone", null, new String[] {"Rock"}));
        // Every authored route misses: false.
        assertFalse(ItemMatch.any("Rock_Stone_Brick", Map.of("Type", new String[] {"Ore"}), "Metal",
                "Rock_Stone", Map.of("Type", new String[] {"Rock"}), new String[] {"Rock"}));
    }

    @Test
    void any_noRouteAuthoredIsFalse_theCallerOwnsThatMeaning() {
        assertFalse(ItemMatch.any(null, null, null,
                "Rock_Stone", Map.of("Type", new String[] {"Rock"}), new String[] {"Rock"}));
        assertFalse(ItemMatch.any(" ", Map.of(), "",
                "Rock_Stone", Map.of("Type", new String[] {"Rock"}), new String[] {"Rock"}));
    }
}
