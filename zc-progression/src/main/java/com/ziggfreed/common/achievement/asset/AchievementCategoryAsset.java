package com.ziggfreed.common.achievement.asset;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;

/**
 * How one CATEGORY is presented: where it sits in a list, what icon stands for it, what it is
 * called, and the order its subcategories read in. Authored at
 * {@code Server/ZiggfreedCommon/AchievementCategories/<category>.json}, and the asset id IS the
 * category name, lower-cased at decode so a PascalCase filename ({@code Combat.json}) resolves the
 * same category a piece of content writes as {@code "combat"}.
 *
 * <p>This is the presentation half of the shared {@code Listing.Category} leaf. Nothing here decides
 * which content exists or what it is worth: a category is simply the word content files itself
 * under, and this says how that word is drawn.
 *
 * <p>Every field is optional, and an absent one means "leave it as it was": a pack that only wants
 * to change an icon ships a file with nothing but {@code Icon}, and the order and subcategories
 * another pack declared stay. A category no file mentions still works. It sorts after the ordered
 * ones and falls back to whatever the surface draws for an unnamed group.
 *
 * <pre>{@code
 * {
 *   "Order": 0,
 *   "Icon": "Weapon_Longsword_Iron",
 *   "TitleKey": "yourmod.category.combat",
 *   "Subcategories": ["melee", "ranged", "damage", "casting", "bosses"]
 * }
 * }</pre>
 *
 * <p>Tip: {@code Order} is a sort key, not an index. Leave gaps (0, 10, 20) so a later category can
 * be slotted between two without renumbering the rest. A subcategory the list does not name still
 * shows; it just sorts after the named ones.
 *
 * <p>Folders under the type root are organisational: the id comes from the FILE name alone, so
 * {@code AchievementCategories/YourMod/Combat.json} is still the category {@code combat}. Two files
 * sharing a basename are therefore one category, and the later one wins.
 */
public final class AchievementCategoryAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, AchievementCategoryAsset>> {

    /** The store's content path under a pack's {@code Server/}. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/AchievementCategories";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Integer order;
    @Nullable protected String icon;
    @Nullable protected String titleKey;
    @Nullable protected String[] subcategories;

    public static final AssetBuilderCodec<String, AchievementCategoryAsset> CODEC = AssetBuilderCodec.builder(
                    AchievementCategoryAsset.class,
                    AchievementCategoryAsset::new,
                    Codec.STRING,
                    // Content writes a category in whatever case reads well; canonicalizing at the
                    // one decode authority keeps getId() the same string everywhere.
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .documentation("Where this category sits among the others, lowest first. It is a sort key rather "
                    + "than an index, so leave gaps (0, 10, 20) and a later category slots between two without "
                    + "renumbering the rest. Unauthored sorts after every category that named one.")
            .add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .documentation("An item id standing for the whole category, shown for content in it that "
                    + "illustrated itself with nothing of its own.")
            .add()
            .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                    (a, v) -> a.titleKey = v, a -> a.titleKey, (a, p) -> a.titleKey = p.titleKey)
            .documentation("The translation key a surface labels this group with, so every player reads it in "
                    + "their own language. Unauthored leaves the label to whatever the surface does by "
                    + "convention.")
            .add()
            .appendInherited(new KeyedCodec<>("Subcategories",
                            new ArrayCodec<>(Codec.STRING, String[]::new), false),
                    (a, v) -> a.subcategories = v, a -> a.subcategories,
                    (a, p) -> a.subcategories = p.subcategories)
            .documentation("The reading order of the groups INSIDE this category. One left out still shows, it "
                    + "just sorts after the named ones. This is ONE leaf: author it and an inherited list is "
                    + "replaced whole.")
            .add()
            .build();

    public AchievementCategoryAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static AchievementCategoryAsset of(@Nonnull String id, @Nullable Integer order,
            @Nullable String icon, @Nullable String titleKey, @Nullable List<String> subcategories) {
        AchievementCategoryAsset a = new AchievementCategoryAsset();
        a.id = id.toLowerCase(Locale.ROOT);
        a.order = order;
        a.icon = icon;
        a.titleKey = titleKey;
        a.subcategories = subcategories == null ? null : subcategories.toArray(new String[0]);
        return a;
    }

    /** The lower-cased category name this presentation applies to. */
    @Override
    public String getId() {
        return id;
    }

    /** Sort key among categories, or null to sort after every ordered one. */
    @Nullable
    public Integer getOrder() {
        return order;
    }

    /** Sort key, or {@link Integer#MAX_VALUE} when the file named none. */
    public int orderOrLast() {
        return order == null ? Integer.MAX_VALUE : order;
    }

    /** Fallback icon item id for content in this category, or null. */
    @Nullable
    public String getIcon() {
        return icon == null || icon.isBlank() ? null : icon;
    }

    /** The translation key a surface labels this category with, or null. */
    @Nullable
    public String getTitleKey() {
        return titleKey == null || titleKey.isBlank() ? null : titleKey;
    }

    /** Subcategory ids in reading order; empty when the file names none. */
    @Nonnull
    public List<String> getSubcategories() {
        return subcategories == null ? List.of() : List.of(subcategories);
    }
}
