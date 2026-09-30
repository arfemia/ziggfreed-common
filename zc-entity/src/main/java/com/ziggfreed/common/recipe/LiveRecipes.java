package com.ziggfreed.common.recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.ziggfreed.common.util.SafeLog;

/**
 * The thin live adapter under {@link RecipeIndex#live()}: one walk over the engine's
 * {@code CraftingRecipe} and {@code Item} asset maps into the plain values {@link RecipeCatalog}
 * indexes. Everything that decides anything is in the catalog; this only reads.
 *
 * <p><b>One walk covers both recipe kinds.</b> The engine loads the recipes authored inside items
 * into the same store the standalone recipe files load into, so the store alone is complete; the
 * item map is read for two things the store does not say: which recipe is an item's own (its
 * {@code <ItemId>_Recipe_Generated_0} id) and each item's tags and resource families, which a
 * reverse lookup recognises it by.
 *
 * <p><b>Tag names.</b> A recipe line selecting by item tag keeps only the tag's registered INDEX at
 * this seam ({@code MaterialQuantity} has no tag-name getter), so the names are recovered from the
 * items themselves: every raw tag key an item carries is resolved to its index once per build. A
 * tag no loaded item carries has no name here, and its line keeps no route, which is also what the
 * engine would find it satisfies.
 */
final class LiveRecipes {

    private LiveRecipes() {
    }

    /** Each skipped item or recipe is named in the log once per JVM, keyed {@code <kind>:<id>}. */
    private static final Set<String> WARNED_SKIPPED = ConcurrentHashMap.newKeySet();

    /** The id a skipped entry is named under when reading its own id is what threw. */
    private static final String UNREADABLE_ID = "<unreadable id>";

    /**
     * A catalog of the live stores, or null when they cannot be read yet (a unit JVM, very early
     * boot). An item or recipe whose own read throws (a third party's malformed asset) is left out
     * and named in the log once, and everything else is still indexed: one bad entry never empties
     * the whole index.
     */
    @Nullable
    static RecipeCatalog read() {
        Map<String, Item> items;
        Map<String, CraftingRecipe> recipeAssets;
        try {
            items = Item.getAssetMap().getAssetMap();
            recipeAssets = CraftingRecipe.getAssetMap().getAssetMap();
        } catch (Throwable t) {
            SafeLog.fine("[recipe] native recipe stores not readable yet: " + t);
            return null;
        }
        Map<Integer, String> tagNames = new HashMap<>();
        Map<String, String> ownerByRecipeId = new HashMap<>();
        List<ItemIdentity> identities = new ArrayList<>(items.size());
        for (Item item : items.values()) {
            if (item == null) {
                continue;
            }
            String id = null;
            try {
                id = item.getId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                Map<String, String[]> tags = rawTagsOf(item);
                ItemIdentity identity = new ItemIdentity(id, tags, resourceTypesOf(item));
                String ownRecipeId = item.hasRecipesToGenerate() ? CraftingRecipe.generateIdFromItemRecipe(item, 0) : null;
                Map<Integer, String> ownTagNames = new HashMap<>();
                collectTagNames(tags, ownTagNames);
                // Everything above is read before anything shared is touched, so an item whose read
                // throws part way leaves no tag name, identity or recipe owner behind.
                identities.add(identity);
                ownTagNames.forEach(tagNames::putIfAbsent);
                if (ownRecipeId != null) {
                    ownerByRecipeId.put(ownRecipeId, id);
                }
            } catch (Throwable t) {
                warnSkipped("item", id, t);
            }
        }
        List<NativeRecipe> recipes = new ArrayList<>(recipeAssets.size());
        for (CraftingRecipe recipe : recipeAssets.values()) {
            if (recipe == null) {
                continue;
            }
            String id = null;
            try {
                id = recipe.getId();
                if (id == null) {
                    continue;
                }
                recipes.add(toRecipe(recipe, tagNames, ownerByRecipeId.get(id)));
            } catch (Throwable t) {
                warnSkipped("recipe", id, t);
            }
        }
        return RecipeCatalog.of(recipes, identities);
    }

    /**
     * Name a skipped entry once per kind and id, so a bad asset is visible without a log line per
     * read. An entry whose id itself could not be read is named under {@link #UNREADABLE_ID}, so every
     * such entry of one kind shares one warning.
     */
    private static void warnSkipped(@Nonnull String kind, @Nullable String id, @Nonnull Throwable cause) {
        String named = id == null ? UNREADABLE_ID : id;
        if (WARNED_SKIPPED.add(kind + ":" + named)) {
            SafeLog.warn("[recipe] " + kind + " '" + named + "' could not be read and is left out of the recipe index: "
                    + cause);
        }
    }

    @Nonnull
    private static NativeRecipe toRecipe(@Nonnull CraftingRecipe recipe, @Nonnull Map<Integer, String> tagNames,
            @Nullable String ownerItemId) {
        MaterialQuantity primary = recipe.getPrimaryOutput();
        return new NativeRecipe(recipe.getId(),
                materials(recipe.getInput(), tagNames),
                materials(recipe.getOutputs(), tagNames),
                primary == null ? null : material(primary, tagNames),
                benches(recipe.getBenchRequirement()),
                recipe.getTimeSeconds(),
                ownerItemId);
    }

    @Nonnull
    private static List<RecipeMaterial> materials(@Nullable MaterialQuantity[] lines,
            @Nonnull Map<Integer, String> tagNames) {
        if (lines == null) {
            return List.of();
        }
        List<RecipeMaterial> out = new ArrayList<>(lines.length);
        for (MaterialQuantity line : lines) {
            if (line != null) {
                out.add(material(line, tagNames));
            }
        }
        return out;
    }

    /** One line, keeping the first authored route in the engine's order: item, then tag, then resource. */
    @Nonnull
    private static RecipeMaterial material(@Nonnull MaterialQuantity line, @Nonnull Map<Integer, String> tagNames) {
        Set<String> excluded = line.getExcludedItemIds();
        Set<String> exclusions = excluded == null ? Set.of() : excluded;
        if (line.getItemId() != null && !line.getItemId().isBlank()) {
            return new RecipeMaterial(line.getItemId(), null, null, line.getQuantity(), exclusions);
        }
        if (line.getTagIndex() != AssetRegistry.TAG_NOT_FOUND) {
            return new RecipeMaterial(null, tagNames.get(line.getTagIndex()), null, line.getQuantity(), exclusions);
        }
        return new RecipeMaterial(null, null, line.getResourceTypeId(), line.getQuantity(), exclusions);
    }

    @Nonnull
    private static List<RecipeBench> benches(@Nullable BenchRequirement[] requirements) {
        if (requirements == null) {
            return List.of();
        }
        List<RecipeBench> out = new ArrayList<>(requirements.length);
        for (BenchRequirement bench : requirements) {
            if (bench != null) {
                out.add(new RecipeBench(bench.id, bench.type == null ? null : bench.type.name(),
                        bench.categories == null ? List.of() : Arrays.asList(bench.categories), bench.requiredTierLevel));
            }
        }
        return out;
    }

    @Nonnull
    private static Map<String, String[]> rawTagsOf(@Nonnull Item item) {
        AssetExtraInfo.Data data = item.getData();
        Map<String, String[]> raw = data == null ? null : data.getRawTags();
        return raw == null ? Map.of() : raw;
    }

    private static void collectTagNames(@Nonnull Map<String, String[]> tags, @Nonnull Map<Integer, String> names) {
        for (String key : tags.keySet()) {
            if (key != null) {
                int index = AssetRegistry.getTagIndex(key);
                if (index != AssetRegistry.TAG_NOT_FOUND) {
                    names.putIfAbsent(index, key);
                }
            }
        }
    }

    @Nonnull
    private static List<String> resourceTypesOf(@Nonnull Item item) {
        ItemResourceType[] types = item.getResourceTypes();
        if (types == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(types.length);
        for (ItemResourceType type : types) {
            if (type != null && type.id != null) {
                out.add(type.id);
            }
        }
        return out;
    }
}
