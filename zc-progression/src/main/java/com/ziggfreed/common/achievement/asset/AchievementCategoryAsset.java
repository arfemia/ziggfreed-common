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
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.progress.asset.CategoryPresentationAsset;

/**
 * How one CATEGORY is presented: where it sits in a list, what icon stands for it, what it is
 * called, its accent, the order its subcategories read in, and the calendar event it belongs to.
 * Authored at {@code Server/ZiggfreedCommon/AchievementCategories/<category>.json}, and the asset id
 * IS the category name, lower-cased at decode so a PascalCase filename ({@code Combat.json})
 * resolves the same category a piece of content writes as {@code "combat"}.
 *
 * <p>This is the presentation half of the shared {@code Listing.Category} leaf. Nothing here decides
 * which content exists or what it is worth: a category is simply the word content files itself
 * under, and this says how that word is drawn. The four leaves every category type shares ({@code
 * Order}, {@code Icon}, {@code TitleKey}, {@code Accent}) come from {@link CategoryPresentationAsset}.
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
 *   "Accent": "#c0504d",
 *   "Subcategories": ["melee", "ranged", "damage", "casting", "bosses"]
 * }
 * }</pre>
 *
 * <p>A category whose groups are each a recurring event files every subcategory under that event's
 * id and says so with {@code "SubcategoryEvents": true}: a listing then marks a group as on now
 * while its event runs. A category belonging to one event as a whole names it in {@code Event}.
 *
 * <p>Tip: {@code Order} is a sort key, not an index. Leave gaps (0, 10, 20) so a later category can
 * be slotted between two without renumbering the rest. A subcategory the list does not name still
 * shows; it just sorts after the named ones.
 *
 * <p>Folders under the type root are organisational: the id comes from the FILE name alone, so
 * {@code AchievementCategories/YourMod/Combat.json} is still the category {@code combat}. Two files
 * sharing a basename are therefore one category, and the later one wins.
 */
public final class AchievementCategoryAsset extends CategoryPresentationAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, AchievementCategoryAsset>> {

    /** The store's content path under a pack's {@code Server/}. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/AchievementCategories";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected String[] subcategories;
    @Nullable protected String event;
    @Nullable protected Boolean subcategoryEvents;

    public static final AssetBuilderCodec<String, AchievementCategoryAsset> CODEC = appendLeaves(
            AssetBuilderCodec.builder(
                    AchievementCategoryAsset.class,
                    AchievementCategoryAsset::new,
                    Codec.STRING,
                    // Content writes a category in whatever case reads well; canonicalizing at the
                    // one decode authority keeps getId() the same string everywhere.
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data))
            .appendInherited(new KeyedCodec<>("Subcategories",
                            new ArrayCodec<>(Codec.STRING, String[]::new), false),
                    (a, v) -> a.subcategories = v, a -> a.subcategories,
                    (a, p) -> a.subcategories = p.subcategories)
            .documentation("The reading order of the groups INSIDE this category. One left out still shows, it "
                    + "just sorts after the named ones. This is ONE leaf: author it and an inherited list is "
                    + "replaced whole.")
            .add()
            .appendInherited(new KeyedCodec<>("Event", Codec.STRING, false),
                    (a, v) -> a.event = v, a -> a.event, (a, p) -> a.event = p.event)
            .documentation("The calendar event this whole category belongs to, by the event's id (its file name). "
                    + "A listing marks the category as on now while that event runs. Unauthored ties it to no "
                    + "event.")
            .add()
            .appendInherited(new KeyedCodec<>("SubcategoryEvents", Codec.BOOLEAN, false),
                    (a, v) -> a.subcategoryEvents = v, a -> a.subcategoryEvents,
                    (a, p) -> a.subcategoryEvents = p.subcategoryEvents)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("Each subcategory in this category is a calendar event, filed under that event's own "
                    + "id (one group per season, say). A listing marks a group as on now while its event runs, "
                    + "and a group nothing else names reads its event's own name. Unauthored means false.")
            .add()
            .build();

    public AchievementCategoryAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static AchievementCategoryAsset of(@Nonnull String id, @Nullable Integer order,
            @Nullable String icon, @Nullable String titleKey, @Nullable List<String> subcategories) {
        return of(id, order, icon, titleKey, subcategories, null, null, null);
    }

    /** Java-side factory with every leaf; sets the same fields the codec fills. */
    @Nonnull
    public static AchievementCategoryAsset of(@Nonnull String id, @Nullable Integer order,
            @Nullable String icon, @Nullable String titleKey, @Nullable List<String> subcategories,
            @Nullable String accent, @Nullable String event, @Nullable Boolean subcategoryEvents) {
        AchievementCategoryAsset a = new AchievementCategoryAsset();
        a.id = id.toLowerCase(Locale.ROOT);
        a.order = order;
        a.icon = icon;
        a.titleKey = titleKey;
        a.subcategories = subcategories == null ? null : subcategories.toArray(new String[0]);
        a.accent = accent;
        a.event = event;
        a.subcategoryEvents = subcategoryEvents;
        return a;
    }

    /** The lower-cased category name this presentation applies to. */
    @Override
    public String getId() {
        return id;
    }

    /** Subcategory ids in reading order; empty when the file names none. */
    @Nonnull
    public List<String> getSubcategories() {
        return subcategories == null ? List.of() : List.of(subcategories);
    }

    /** The calendar event this whole category belongs to, lower-cased, or null for none. */
    @Nullable
    public String getEvent() {
        return canonicalEvent(event);
    }

    /** Is every subcategory in this category filed under a calendar event's own id? Unauthored: no. */
    public boolean isSubcategoryEvents() {
        return subcategoryEvents != null && subcategoryEvents;
    }

    /**
     * The calendar event a group inside this category rides, lower-cased, or null for none: the
     * subcategory's own id when {@link #isSubcategoryEvents()} and a subcategory is named, else the
     * category's own {@link #getEvent()}. The one reading of the two leaves, so a tile, a section and
     * a row all agree on whether a group is on now.
     */
    @Nullable
    public String eventFor(@Nullable String subcategory) {
        if (isSubcategoryEvents() && subcategory != null && !subcategory.isBlank()) {
            return canonicalEvent(subcategory);
        }
        return getEvent();
    }

    @Nullable
    private static String canonicalEvent(@Nullable String written) {
        return written == null || written.isBlank() ? null : written.trim().toLowerCase(Locale.ROOT);
    }
}
