package com.ziggfreed.common.recipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An immutable snapshot of every native recipe, indexed for the questions a consumer deriving from
 * engine recipes asks: which recipes a bench makes, which consume an item, which produce one, and
 * which is an item's own.
 *
 * <p><b>Pure.</b> It is built from plain values ({@link NativeRecipe}, {@link ItemIdentity}) and
 * reads no engine state, so a test builds one over hand-written recipes and a live server builds one
 * over the engine's store through {@link RecipeIndex}; both answer through this same code. Every
 * list it returns is unmodifiable and ordered by recipe id (case-insensitive), so a consumer that
 * derives rows from it derives them in the same order on every boot.
 *
 * <p>Ids are matched without regard to case throughout, the family's rule for authored ids.
 */
public final class RecipeCatalog {

    private static final RecipeCatalog EMPTY = new RecipeCatalog(List.of(), List.of());

    @Nonnull private final List<NativeRecipe> all;
    @Nonnull private final Map<String, NativeRecipe> byId = new HashMap<>();
    @Nonnull private final Map<String, List<NativeRecipe>> byBench = new HashMap<>();
    @Nonnull private final Map<String, List<NativeRecipe>> byOutputItem = new HashMap<>();
    @Nonnull private final Map<String, List<NativeRecipe>> byInputItem = new HashMap<>();
    @Nonnull private final List<NativeRecipe> openInputRecipes = new ArrayList<>();
    @Nonnull private final Map<String, NativeRecipe> byOwner = new HashMap<>();
    @Nonnull private final Map<String, ItemIdentity> identities = new HashMap<>();

    private RecipeCatalog(@Nonnull Collection<NativeRecipe> recipes, @Nonnull Collection<ItemIdentity> items) {
        List<NativeRecipe> sorted = new ArrayList<>();
        for (NativeRecipe recipe : recipes) {
            if (recipe != null && !recipe.id().isEmpty() && byId.putIfAbsent(key(recipe.id()), recipe) == null) {
                sorted.add(recipe);
            }
        }
        sorted.sort(Comparator.comparing(NativeRecipe::id, String.CASE_INSENSITIVE_ORDER));
        this.all = List.copyOf(sorted);
        for (NativeRecipe recipe : all) {
            indexBenches(recipe);
            indexOutputs(recipe);
            indexInputs(recipe);
            if (recipe.ownerItemId() != null) {
                byOwner.putIfAbsent(key(recipe.ownerItemId()), recipe);
            }
        }
        for (ItemIdentity item : items) {
            if (item != null && !item.itemId().isEmpty()) {
                identities.putIfAbsent(key(item.itemId()), item);
            }
        }
        byBench.replaceAll((id, list) -> List.copyOf(list));
        byOutputItem.replaceAll((id, list) -> List.copyOf(list));
        byInputItem.replaceAll((id, list) -> List.copyOf(list));
    }

    /**
     * A catalog over {@code recipes}, with {@code items} as the identities a reverse lookup by item
     * id resolves through (an id missing from them is known by its id alone). A second recipe under
     * an id already taken is ignored.
     */
    @Nonnull
    public static RecipeCatalog of(@Nonnull Collection<NativeRecipe> recipes, @Nonnull Collection<ItemIdentity> items) {
        return new RecipeCatalog(recipes, items);
    }

    /** A catalog holding nothing: what a server reads before any recipe has loaded. */
    @Nonnull
    public static RecipeCatalog empty() {
        return EMPTY;
    }

    /** Every recipe, ordered by id. */
    @Nonnull
    public List<NativeRecipe> all() {
        return all;
    }

    /** How many recipes the catalog holds. */
    public int size() {
        return all.size();
    }

    /** The recipe stored under {@code id}, or null. */
    @Nullable
    public NativeRecipe recipe(@Nullable String id) {
        return id == null ? null : byId.get(key(id));
    }

    /** Every recipe made at the bench named {@code benchId} ({@code "Salvagebench"}), standalone or inline. */
    @Nonnull
    public List<NativeRecipe> atBench(@Nullable String benchId) {
        return benchId == null ? List.of() : byBench.getOrDefault(key(benchId), List.of());
    }

    /** Every recipe with an output line giving {@code itemId}. */
    @Nonnull
    public List<NativeRecipe> producing(@Nullable String itemId) {
        return itemId == null ? List.of() : byOutputItem.getOrDefault(key(itemId), List.of());
    }

    /**
     * Every recipe with an input line that would take {@code itemId}: by its exact id, by a tag it
     * carries, or by a resource family it counts toward, after the engine's own exclusions. The item
     * is recognised through the identities this catalog was built with, so a tag or family line
     * finds it only when the catalog knows that item.
     */
    @Nonnull
    public List<NativeRecipe> consuming(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        ItemIdentity known = identities.get(key(itemId));
        return consuming(known != null ? known : ItemIdentity.ofId(itemId));
    }

    /** {@link #consuming(String)} for an identity the caller already holds. */
    @Nonnull
    public List<NativeRecipe> consuming(@Nonnull ItemIdentity item) {
        if (item.itemId().isEmpty()) {
            return List.of();
        }
        Set<NativeRecipe> found = new LinkedHashSet<>();
        for (NativeRecipe recipe : byInputItem.getOrDefault(key(item.itemId()), List.of())) {
            if (recipe.consumes(item)) {
                found.add(recipe);
            }
        }
        for (NativeRecipe recipe : openInputRecipes) {
            if (recipe.consumes(item)) {
                found.add(recipe);
            }
        }
        List<NativeRecipe> out = new ArrayList<>(found);
        out.sort(Comparator.comparing(NativeRecipe::id, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    /**
     * The item's OWN crafting recipe: the one authored inside the item
     * ({@code <ItemId>_Recipe_Generated_<n>}), or null when the item authors none. A standalone
     * recipe that happens to produce the item is {@link #producing}'s answer, not this one.
     */
    @Nullable
    public NativeRecipe craftingRecipeOf(@Nullable String itemId) {
        return itemId == null ? null : byOwner.get(key(itemId));
    }

    // ==================== index build ====================

    private void indexBenches(@Nonnull NativeRecipe recipe) {
        Set<String> seen = new LinkedHashSet<>();
        for (RecipeBench bench : recipe.benches()) {
            if (!bench.id().isEmpty() && seen.add(key(bench.id()))) {
                append(byBench, bench.id(), recipe);
            }
        }
    }

    private void indexOutputs(@Nonnull NativeRecipe recipe) {
        Set<String> seen = new LinkedHashSet<>();
        for (RecipeMaterial output : recipe.outputs()) {
            if (output.itemId() != null && seen.add(key(output.itemId()))) {
                append(byOutputItem, output.itemId(), recipe);
            }
        }
    }

    /**
     * Exact-item input lines are indexed by id; a recipe with any tag or resource line is kept on
     * the open list, which a reverse lookup scans, because which items such a line takes depends on
     * the item being asked about.
     */
    private void indexInputs(@Nonnull NativeRecipe recipe) {
        Set<String> seen = new LinkedHashSet<>();
        boolean open = false;
        for (RecipeMaterial input : recipe.inputs()) {
            if (input.itemId() != null) {
                if (seen.add(key(input.itemId()))) {
                    append(byInputItem, input.itemId(), recipe);
                }
            } else if (input.hasRoute()) {
                open = true;
            }
        }
        if (open) {
            openInputRecipes.add(recipe);
        }
    }

    private static void append(@Nonnull Map<String, List<NativeRecipe>> index, @Nonnull String id,
            @Nonnull NativeRecipe recipe) {
        index.computeIfAbsent(key(id), k -> new ArrayList<>()).add(recipe);
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
