package com.ziggfreed.common.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The recipe index's pure core over hand-written recipes shaped like the engine's two kinds: a
 * STANDALONE salvage recipe (its own file, one input, a full output list, one Processing bench) and
 * an INLINE crafting recipe (authored inside an item, loaded under {@code <ItemId>_Recipe_Generated_0},
 * its outputs the primary output at its own quantity). Every id and number is the test's own.
 */
class RecipeCatalogTest {

    private static final String SALVAGE_BENCH = "Test_Salvagebench";
    private static final String WORKBENCH = "Test_Workbench";

    private static RecipeBench bench(String id, String type) {
        return new RecipeBench(id, type, List.of("Test_Category"), 0);
    }

    /** A standalone salvage recipe: one sword in, the full return out. */
    private static NativeRecipe salvageSword() {
        return new NativeRecipe("Salvage_Test_Sword",
                List.of(RecipeMaterial.item("Test_Sword", 1)),
                List.of(RecipeMaterial.item("Test_Ore", 2), RecipeMaterial.item("Test_Hide", 1),
                        RecipeMaterial.item("Test_Scrap", 3)),
                RecipeMaterial.item("Test_Ore", 2),
                List.of(bench(SALVAGE_BENCH, "Processing")), 4f, null);
    }

    /** The sword's own inline recipe: two ingots and a handle wrap, at the workbench. */
    private static NativeRecipe craftSword() {
        return new NativeRecipe("Test_Sword_Recipe_Generated_0",
                List.of(RecipeMaterial.item("Test_Ingot", 2), RecipeMaterial.tagged("Test_Wrap", 1)),
                List.of(RecipeMaterial.item("Test_Sword", 1)),
                RecipeMaterial.item("Test_Sword", 1),
                List.of(bench(WORKBENCH, "Crafting")), 2f, "Test_Sword");
    }

    /** An inline plank recipe whose one output line carries its full quantity. */
    private static NativeRecipe craftPlanks() {
        return new NativeRecipe("Test_Planks_Recipe_Generated_0",
                List.of(RecipeMaterial.resource("Test_Wood", 1)),
                List.of(RecipeMaterial.item("Test_Planks", 4)),
                RecipeMaterial.item("Test_Planks", 4),
                List.of(bench(WORKBENCH, "Crafting")), 1f, "Test_Planks");
    }

    private static RecipeCatalog catalog() {
        return RecipeCatalog.of(List.of(salvageSword(), craftSword(), craftPlanks()), List.of(
                new ItemIdentity("Test_Linen", Map.of("Test_Wrap", new String[0]), List.of()),
                new ItemIdentity("Test_Log", Map.of(), List.of("Test_Wood"))));
    }

    @Test
    void standaloneAndInlineRecipesSitInOneCatalogWithTheirFullQuantities() {
        RecipeCatalog catalog = catalog();

        assertEquals(3, catalog.size());
        NativeRecipe salvage = catalog.recipe("salvage_test_sword");
        assertNotNull(salvage, "ids are matched without regard to case");
        assertFalse(salvage.isInline(), "a salvage file is a standalone recipe");
        assertEquals(List.of(2, 1, 3), salvage.outputs().stream().map(RecipeMaterial::quantity).toList(),
                "every output line keeps its own quantity");
        assertEquals(4, catalog.recipe("Test_Planks_Recipe_Generated_0").outputs().get(0).quantity(),
                "an inline recipe's output carries its full quantity, not 1");
        assertTrue(catalog.recipe("Test_Sword_Recipe_Generated_0").isInline());
    }

    @Test
    void aBenchListsEveryRecipeMadeThereInIdOrder() {
        RecipeCatalog catalog = catalog();

        assertEquals(List.of("Salvage_Test_Sword"), ids(catalog.atBench("test_salvagebench")));
        assertEquals(List.of("Test_Planks_Recipe_Generated_0", "Test_Sword_Recipe_Generated_0"),
                ids(catalog.atBench(WORKBENCH)));
        assertTrue(catalog.atBench("Test_Unknown_Bench").isEmpty());
        assertTrue(catalog.atBench(null).isEmpty());
    }

    @Test
    void theRecipesThatConsumeAnItemIncludeItsSalvage() {
        RecipeCatalog catalog = catalog();

        assertEquals(List.of("Salvage_Test_Sword"), ids(catalog.consuming("Test_Sword")));
        assertEquals(List.of("Test_Sword_Recipe_Generated_0"), ids(catalog.consuming("Test_Ingot")));
    }

    @Test
    void aTagOrResourceLineFindsAnItemTheCatalogKnows() {
        RecipeCatalog catalog = catalog();

        assertEquals(List.of("Test_Sword_Recipe_Generated_0"), ids(catalog.consuming("Test_Linen")),
                "Test_Linen carries the tag the sword's wrap line asks for");
        assertEquals(List.of("Test_Planks_Recipe_Generated_0"), ids(catalog.consuming("Test_Log")),
                "Test_Log counts toward the resource family the plank line asks for");
        assertTrue(catalog.consuming("Test_Stranger").isEmpty(),
                "an item the catalog does not know is recognised by id alone");
    }

    @Test
    void anExcludedItemIsNeverTakenByATagLine() {
        NativeRecipe wrapOnly = new NativeRecipe("Test_Wrap_Recipe",
                List.of(new RecipeMaterial(null, "Test_Wrap", null, 1, Set.of("test_linen"))),
                List.of(RecipeMaterial.item("Test_Linen", 1)), null, List.of(), 0f, null);
        RecipeCatalog catalog = RecipeCatalog.of(List.of(wrapOnly), List.of(
                new ItemIdentity("Test_Linen", Map.of("Test_Wrap", new String[0]), List.of())));

        assertTrue(catalog.consuming("Test_Linen").isEmpty(),
                "a recipe never consumes its own output as a generic ingredient");
    }

    @Test
    void theRecipesThatProduceAnItem() {
        RecipeCatalog catalog = catalog();

        assertEquals(List.of("Salvage_Test_Sword"), ids(catalog.producing("Test_Ore")));
        assertEquals(List.of("Test_Sword_Recipe_Generated_0"), ids(catalog.producing("test_sword")));
        assertTrue(catalog.producing("Test_Nothing").isEmpty());
    }

    @Test
    void anItemsOwnCraftingRecipeIsTheInlineOneOnly() {
        RecipeCatalog catalog = catalog();

        assertEquals("Test_Sword_Recipe_Generated_0", catalog.craftingRecipeOf("TEST_SWORD").id());
        assertNull(catalog.craftingRecipeOf("Test_Ore"),
                "an item produced only by a standalone recipe authors no crafting recipe of its own");
    }

    @Test
    void aLineKeepsTheFirstRouteInTheEnginesOrder() {
        RecipeMaterial all = new RecipeMaterial("Test_Ingot", "Test_Wrap", "Test_Wood", 0, null);

        assertEquals("Test_Ingot", all.itemId());
        assertNull(all.tag());
        assertNull(all.resourceTypeId());
        assertEquals(1, all.quantity(), "a quantity is at least 1");
        assertFalse(new RecipeMaterial(null, null, null, 1, null).hasRoute());
    }

    @Test
    void aSecondRecipeUnderATakenIdIsIgnored() {
        NativeRecipe impostor = new NativeRecipe("SALVAGE_TEST_SWORD", List.of(), List.of(), null,
                List.of(), 0f, null);
        RecipeCatalog catalog = RecipeCatalog.of(List.of(salvageSword(), impostor), List.of());

        assertEquals(1, catalog.size());
        assertEquals(3, catalog.recipe("Salvage_Test_Sword").outputs().size());
    }

    @Test
    void everyListHandedOutIsUnmodifiable() {
        RecipeCatalog catalog = catalog();

        assertThrows(UnsupportedOperationException.class, () -> catalog.all().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.atBench(WORKBENCH).clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.producing("Test_Ore").clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.consuming("Test_Sword").clear());
    }

    /**
     * The engine merges into an item's live raw-tag map on a reload, so an identity built from it
     * keeps a copy: a change to the source map afterwards, or to one of its value arrays, is not
     * seen, and the identity's own map refuses writes.
     */
    @Test
    void anIdentityKeepsACopyOfTheTagMapItWasBuiltFrom() {
        String[] values = {"Test_Linen"};
        Map<String, String[]> live = new HashMap<>();
        live.put("Test_Wrap", values);
        ItemIdentity identity = new ItemIdentity("Test_Bandage", live, List.of());

        live.put("Test_Late", new String[0]);
        values[0] = "Test_Changed";

        assertEquals(Set.of("Test_Wrap"), identity.tags().keySet());
        assertEquals("Test_Linen", identity.tags().get("Test_Wrap")[0]);
        assertThrows(UnsupportedOperationException.class, () -> identity.tags().put("Test_Other", new String[0]));
    }

    @Test
    void theEmptyCatalogAnswersNothing() {
        RecipeCatalog empty = RecipeCatalog.empty();

        assertSame(empty, RecipeCatalog.empty());
        assertEquals(0, empty.size());
        assertTrue(empty.consuming("Test_Sword").isEmpty());
        assertNull(empty.craftingRecipeOf("Test_Sword"));
    }

    private static List<String> ids(List<NativeRecipe> recipes) {
        return recipes.stream().map(NativeRecipe::id).toList();
    }
}
