package com.ziggfreed.common.quest.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.ziggfreed.common.progress.asset.CategoryPresentationAsset;

/**
 * How one QUEST category is presented: where it sits in a list, what icon stands for it, what it is
 * called (its accent is decoded and kept, but no surface draws it yet). Authored at
 * {@code Server/ZiggfreedCommon/QuestCategories/<category>.json}; the asset id IS the category name,
 * lower-cased at decode, so {@code Errands.json} describes the category a quest's
 * {@code Listing.Category} writes as {@code "errands"}.
 *
 * <p>The presentation half of a quest's {@code Listing.Category} leaf, with exactly the leaves every
 * category type shares ({@link CategoryPresentationAsset}): nothing here decides which quests exist,
 * and a category no file mentions still works, sorting after the described ones and reading its
 * convention key ({@code quest.category.<id>}) or its id as words.
 *
 * <pre>{@code
 * { "Order": 0, "Icon": "Deco_Scroll", "TitleKey": "yourmod.quest.category.errands", "Accent": "#4a9eff" }
 * }</pre>
 *
 * <p>Folders under the type root are organisational: the id comes from the FILE name alone, so two
 * files sharing a basename are one category, and the later one wins.
 */
public final class QuestCategoryAsset extends CategoryPresentationAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, QuestCategoryAsset>> {

    /** The store's content path under a pack's {@code Server/}. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/QuestCategories";

    private String id;
    private AssetExtraInfo.Data data;

    public static final AssetBuilderCodec<String, QuestCategoryAsset> CODEC = appendLeaves(
            AssetBuilderCodec.builder(
                    QuestCategoryAsset.class,
                    QuestCategoryAsset::new,
                    Codec.STRING,
                    // A quest writes its category in whatever case reads well; one casing at the
                    // decode keeps getId() the same string everywhere it is compared.
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data))
            .build();

    public QuestCategoryAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static QuestCategoryAsset of(@Nonnull String id, @Nullable Integer order, @Nullable String icon,
            @Nullable String titleKey, @Nullable String accent) {
        QuestCategoryAsset a = new QuestCategoryAsset();
        a.id = id.toLowerCase(Locale.ROOT);
        a.order = order;
        a.icon = icon;
        a.titleKey = titleKey;
        a.accent = accent;
        return a;
    }

    /** The lower-cased category name this presentation applies to. */
    @Override
    public String getId() {
        return id;
    }
}
