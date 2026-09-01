package com.ziggfreed.common.loot.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * The base-description GATE: which of an item's own description keys, if any, is safe to nest above
 * the enhancement lines.
 *
 * <p>Two failures it exists to prevent, both of which reach a player as visible nonsense rather than
 * as an error. Nesting a key the catalogue does not carry prints the key itself on the item. And
 * nesting a description that carries markup prints the tags literally, because this surface has no
 * markup parser - so a marked-up description earns no base prose at all rather than a tooltip full
 * of angle brackets.
 *
 * <p>Pure: no item, no server, no catalogue. The two probes are passed in.
 */
class StampTooltipGateTest {


    // --- baseDescriptionKey: base-KEY selection never derives a phantom/unresolvable key ---

    private static final String CRUDE_SWORD_DESC_KEY = "server.items.Weapon_Sword_Crude.description";

    /** Always false: no key ever carries markup (used where the markup gate is not under test). */
    private static final Predicate<String> NO_MARKUP = k -> false;

    @Test
    void baseDescriptionKey_nonToolWithRealMarkupFreeDescription_nestsOwnKey() {
        // A non-tool item whose own description key exists in the catalog and carries no markup
        // nests that key verbatim.
        Predicate<String> onlyOwnKey = CRUDE_SWORD_DESC_KEY::equals;
        String chosen = StampTooltip.baseDescriptionKey(
                false, "Weapon_Sword_Crude", CRUDE_SWORD_DESC_KEY, onlyOwnKey, NO_MARKUP);
        assertEquals(CRUDE_SWORD_DESC_KEY, chosen);
    }

    @Test
    void baseDescriptionKey_nonToolDescriptionless_isNull_noPhantomLine() {
        // A bare vanilla item with no catalog value for its description key gets a null base,
        // so descriptionFor leads with the "Enhancements" header - no raw-key phantom line.
        String chosen = StampTooltip.baseDescriptionKey(
                false, "Weapon_Sword_Bare", "server.items.Weapon_Sword_Bare.description", k -> false, NO_MARKUP);
        assertNull(chosen);
    }

    @Test
    void baseDescriptionKey_toolStatsTag_withAuthoredBase_nestsBaseKey() {
        // A tool nests its authored markup-free .description.base prose key when present.
        String baseKey = "server.items.Tool_Pickaxe_Copper.description.base";
        String chosen = StampTooltip.baseDescriptionKey(
                true, "Tool_Pickaxe_Copper", "server.items.Tool_Pickaxe_Copper.description",
                baseKey::equals, NO_MARKUP);
        assertEquals(baseKey, chosen);
    }

    @Test
    void baseDescriptionKey_toolStatsTag_withoutAuthoredBase_isNull() {
        // A tool with no authored .description.base gets a null base (never the generated,
        // markup-bearing .description, and never a phantom key).
        String chosen = StampTooltip.baseDescriptionKey(
                true, "Tool_Pickaxe_Copper", "server.items.Tool_Pickaxe_Copper.description", k -> false, NO_MARKUP);
        assertNull(chosen);
    }

    @Test
    void baseDescriptionKey_toolStatsTag_ignoresTheMarkupGate() {
        // A tool never consults keyValueHasMarkup at all: even a Predicate that would always say
        // "markup" doesn't stop the authored .description.base key from being chosen.
        String baseKey = "server.items.Tool_Pickaxe_Copper.description.base";
        String chosen = StampTooltip.baseDescriptionKey(
                true, "Tool_Pickaxe_Copper", "server.items.Tool_Pickaxe_Copper.description",
                baseKey::equals, k -> true);
        assertEquals(baseKey, chosen);
    }

    // --- baseDescriptionKey: the markup gate (settled 2026-07-29 maintainer decision) ---

    @Test
    void baseDescriptionKey_nonToolOwnKeyMissing_isNull() {
        // The own key does not exist in the catalog at all: null, regardless of the markup gate.
        String chosen = StampTooltip.baseDescriptionKey(
                false, "Weapon_Sword_Crude", CRUDE_SWORD_DESC_KEY, k -> false, k -> true);
        assertNull(chosen);
    }

    @Test
    void baseDescriptionKey_nonToolOwnKeyExistsAndCarriesMarkup_isNull() {
        // The own key exists but its lang VALUE carries markup: no base prose at all (stats-only
        // tooltip), since this surface cannot parse the markup.
        String chosen = StampTooltip.baseDescriptionKey(
                false, "Weapon_Sword_Crude", CRUDE_SWORD_DESC_KEY, CRUDE_SWORD_DESC_KEY::equals, k -> true);
        assertNull(chosen);
    }

    @Test
    void baseDescriptionKey_nonToolOwnKeyExistsAndClean_nestsOwnKey() {
        // The own key exists and its lang VALUE has no markup: nest the own key.
        String chosen = StampTooltip.baseDescriptionKey(
                false, "Weapon_Sword_Crude", CRUDE_SWORD_DESC_KEY, CRUDE_SWORD_DESC_KEY::equals, k -> false);
        assertEquals(CRUDE_SWORD_DESC_KEY, chosen);
    }

    // --- hasMarkup: null-safe markup detection ---

    @Test
    void hasMarkup_valueWithTag_isTrue() {
        assertTrue(StampTooltip.hasMarkup("A <i>fine</i> blade."));
    }

    @Test
    void hasMarkup_plainValue_isFalse() {
        assertFalse(StampTooltip.hasMarkup("A sturdy blade."));
    }

    @Test
    void hasMarkup_nullValue_isFalse() {
        // A missing catalog value (getFullKey returned null) has no markup, so the gate falls
        // through to nesting the key the client resolves.
        assertFalse(StampTooltip.hasMarkup(null));
    }
}
