package com.ziggfreed.common.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;

/**
 * An item id followed by the ids of the items it inherits from through the asset {@code Parent}
 * key: a pack's {@code My_Pick}, then {@code Tool_Pickaxe_Crude}.
 *
 * <p>A consumer that classifies held items by matching id patterns ({@code Tool_Pickaxe_} for a
 * pickaxe, {@code Sword} for a sword) matches the whole lineage, the item's own id first. A copy of
 * a base-game item made through {@code Parent} is that item to the engine (it inherits its tool
 * powers, interactions and tags), but its own id rarely carries the pattern; matching the lineage
 * makes a {@code Parent} copy count as what it copies.
 *
 * <p>The parent link is read from the loaded asset's own data
 * ({@code AssetExtraInfo.Data#getParentKey}), the key the asset store resolved the
 * {@code Parent} through. A same-id override (a pack replacing a base item under its own id)
 * names itself as its parent; the walk stops at any id it has already seen and after
 * {@link #MAX_DEPTH} links.
 */
public final class ItemLineage {

    /** The most links followed. Base-game chains are one or two deep. */
    static final int MAX_DEPTH = 8;

    private ItemLineage() {
    }

    /**
     * The held item's id, then each ancestor's id, read from the live item asset map. Empty for
     * a null or blank id; just the id itself when the item is unknown or has no parent.
     */
    @Nonnull
    public static List<String> of(@Nullable String itemId) {
        return of(itemId, ItemLineage::loadedParentOf);
    }

    /**
     * The first id in the lineage, the item's own id first, that {@code test} accepts, or null
     * when none does. Ancestors are looked up only after the item's own id fails the test.
     */
    @Nullable
    public static String firstMatching(@Nullable String itemId, @Nonnull Predicate<String> test) {
        return firstMatching(itemId, test, ItemLineage::loadedParentOf);
    }

    /**
     * {@link #of(String)} over a supplied parent lookup: the seam a unit test drives, since a unit
     * JVM cannot stand up the item store.
     */
    @Nonnull
    public static List<String> of(@Nullable String itemId, @Nonnull Function<String, String> parentOf) {
        List<String> lineage = new ArrayList<>(2);
        String current = itemId;
        while (current != null && !current.isEmpty() && !lineage.contains(current)
                && lineage.size() <= MAX_DEPTH) {
            lineage.add(current);
            current = parentOf.apply(current);
        }
        return lineage;
    }

    /** {@link #firstMatching(String, Predicate)} over a supplied parent lookup. */
    @Nullable
    public static String firstMatching(@Nullable String itemId, @Nonnull Predicate<String> test,
            @Nonnull Function<String, String> parentOf) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        if (test.test(itemId)) {
            return itemId;
        }
        List<String> lineage = of(itemId, parentOf);
        for (int i = 1; i < lineage.size(); i++) {
            if (test.test(lineage.get(i))) {
                return lineage.get(i);
            }
        }
        return null;
    }

    /** The loaded item's {@code Parent} id, or null when it has none or no item store is up. */
    @Nullable
    private static String loadedParentOf(@Nonnull String itemId) {
        AssetStore<String, Item, DefaultAssetMap<String, Item>> store = Item.getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, Item> map = store.getAssetMap();
        Item item = map != null ? map.getAsset(itemId) : null;
        AssetExtraInfo.Data data = item != null ? item.getData() : null;
        if (data == null) {
            return null;
        }
        return data.getParentKey() instanceof String parent && !parent.isEmpty() ? parent : null;
    }
}
