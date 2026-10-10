package com.ziggfreed.common.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * {@link ItemLineage}: an item id, then each id it inherits from through {@code Parent}. The parent
 * links are supplied as a map (the asset store's own {@code Parent} keys are what the live overload
 * reads, which a unit JVM cannot stand up).
 *
 * <p>The case that motivated it: a reward pickaxe authored as a {@code Parent} copy of
 * {@code Tool_Pickaxe_Crude} under its own id is a pickaxe to the engine, but a {@code Tool_Pickaxe_}
 * id pattern matched only its own id and refused every stone and ore it swung at. A consumer tests
 * its own classifiers over the lineage.
 */
class ItemLineageTest {

    private static final Map<String, String> PARENTS = Map.of(
            "Mmo_Delvers_Pick", "Tool_Pickaxe_Crude",
            "Wilds_Prospectors_Pick", "Tool_Pickaxe_Copper",
            "Tool_Pickaxe_Copper", "Tool_Pickaxe_Crude",
            "Mmo_Watchmans_Blade", "Weapon_Sword_Crude");

    private static List<String> lineage(String itemId) {
        return ItemLineage.of(itemId, PARENTS::get);
    }

    // ==================== The walk ====================

    @Test
    void aParentCopyListsItsOwnIdThenItsParent() {
        assertEquals(List.of("Mmo_Delvers_Pick", "Tool_Pickaxe_Crude"), lineage("Mmo_Delvers_Pick"));
    }

    @Test
    void aChainOfParentsIsWalkedNearestFirst() {
        assertEquals(List.of("Wilds_Prospectors_Pick", "Tool_Pickaxe_Copper", "Tool_Pickaxe_Crude"),
                lineage("Wilds_Prospectors_Pick"));
    }

    @Test
    void anItemWithNoParentIsItsOwnLineage() {
        assertEquals(List.of("Tool_Pickaxe_Crude"), lineage("Tool_Pickaxe_Crude"));
    }

    @Test
    void anEmptyHandHasNoLineage() {
        assertTrue(lineage(null).isEmpty());
        assertTrue(lineage("").isEmpty());
    }

    @Test
    void aSameIdOverrideThatNamesItselfAsParentStopsAtItself() {
        assertEquals(List.of("Tool_Pickaxe_Iron"),
                ItemLineage.of("Tool_Pickaxe_Iron", id -> "Tool_Pickaxe_Iron"));
    }

    @Test
    void aParentCycleStopsAtTheFirstRepeat() {
        Map<String, String> cycle = Map.of("A", "B", "B", "A");
        assertEquals(List.of("A", "B"), ItemLineage.of("A", cycle::get));
    }

    @Test
    void anOverlongChainIsCutAfterMaxDepthLinks() {
        Map<String, String> chain = new HashMap<>();
        for (int i = 0; i < ItemLineage.MAX_DEPTH + 5; i++) {
            chain.put("Item_" + i, "Item_" + (i + 1));
        }
        assertEquals(ItemLineage.MAX_DEPTH + 1, ItemLineage.of("Item_0", chain::get).size());
    }

    @Test
    void firstMatchingTriesTheItemsOwnIdBeforeLookingUpAnyParent() {
        List<String> lookedUp = new ArrayList<>();
        Function<String, String> parentOf = id -> {
            lookedUp.add(id);
            return PARENTS.get(id);
        };
        assertEquals("Tool_Pickaxe_Crude",
                ItemLineage.firstMatching("Tool_Pickaxe_Crude", id -> id.contains("Tool_Pickaxe_"), parentOf));
        assertTrue(lookedUp.isEmpty(), "an own-id match needs no parent lookup");
    }

    @Test
    void firstMatchingFallsBackToTheNearestMatchingAncestor() {
        assertEquals("Tool_Pickaxe_Copper", ItemLineage.firstMatching("Wilds_Prospectors_Pick",
                id -> id.contains("Copper"), PARENTS::get));
        assertNull(ItemLineage.firstMatching("Wilds_Prospectors_Pick", id -> id.contains("Sword"), PARENTS::get));
        assertNull(ItemLineage.firstMatching(null, id -> true, PARENTS::get));
    }
}
