package com.ziggfreed.common.shop.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.commerce.asset.CommerceEditorDataSets;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.world.WorldSelector;

/**
 * One STOREFRONT, at {@code Server/ZiggfreedCommon/Shops/<ns>/<Id>.json}. The FILE NAME is the shop
 * id.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "shop.general.title", "FlavorKey": "shop.general.desc" },
 *   "Icon": "Ore_Iron",
 *   "Order": 0,
 *   "Currencies": ["Bounty_Token", "Life_Essence"],
 *   "CategoryOrder": ["Items", "Boosts", "Conversion", "Featured"],
 *   "Categories": { "Relics": { "TitleKey": "shop.category.relics" } } }
 * }</pre>
 *
 * <p>A shop is the PAGE: what it is called, what it looks like, which wallets its header shows, and
 * the order its shelves read in. What is actually for sale is a separate file per offer, each naming
 * this shop - so adding one thing to a storefront never means editing the storefront.
 *
 * <p><b>{@code Currencies} is the balance strip in the header.</b> List every wallet this shop
 * actually prices in, so a player can see what they can afford before they browse; a wallet nobody
 * spends here is noise, and one they DO spend here and cannot see is worse. Leave it out and the
 * header shows nothing.
 *
 * <p><b>{@code CategoryOrder} fixes the order the shelves appear in.</b> Leave it out and categories
 * sort alphabetically, which puts "rare" ahead of "uncommon". Any category not named here follows
 * the listed ones alphabetically, and each offer's own sort order still arranges offers WITHIN one
 * category - so "my rare shelf lists before uncommon" is always a CategoryOrder gap, never an offer's
 * order.
 *
 * <p><b>{@code Categories} is what each shelf SAYS</b>, keyed by the category's own word. The common
 * shelves read in every language without it. Author it for a shelf of your own invention and point
 * its {@code TitleKey} at a line in your own lang file; until you do, the shelf reads as the word you
 * typed, which is honest but is only in one language.
 *
 * <p><b>{@code Where} decides which worlds this storefront exists in at all</b>, in the one
 * world-targeting grammar every file on this server uses. Leave it out and it exists everywhere,
 * which is what a hub shop wants; author it when a storefront belongs to one place.
 *
 * <p>To retune a storefront somebody else shipped, override the file by id (a same-named file in a
 * later pack or in the owner layer {@code mods/ziggfreedcommon/shops.json}), or ship your own with
 * {@code Parent} set to theirs and author only what you change.
 */
public final class StorefrontAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, StorefrontAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Shops";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private ContentTextAsset text;
    @Nullable private String icon;
    @Nullable private Integer order;
    @Nullable private String[] currencies;
    @Nullable private String[] categoryOrder;
    @Nullable private Map<String, ContentTextAsset> categories;
    @Nullable private GateSpec requires;
    @Nullable private WorldSelector where;
    @Nullable private Map<String, JsonElement> meta;

    public static final AssetBuilderCodec<String, StorefrontAsset> CODEC = AssetBuilderCodec.builder(
                    StorefrontAsset.class,
                    StorefrontAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op: the id comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .documentation("Whether the storefront can be opened at all; unauthored means true. Setting false "
                    + "closes it without deleting the offers, so a seasonal shop comes back with one edit.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the player reads at the top of the page, as localization keys.")
            .add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(new UIEditor(new UIEditor.Dropdown("hytale:item")))
            .documentation("The item whose picture stands for this storefront wherever shops are listed side by "
                    + "side.")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .documentation("Lower sorts first where several storefronts are listed; unauthored means 0. Leave "
                    + "gaps (10, 20, 30) so a later one can be slotted between two without renumbering.")
            .add()
            .appendInherited(new KeyedCodec<>("Currencies", Codec.STRING_ARRAY, false),
                    (a, v) -> a.currencies = v, a -> a.currencies, (a, p) -> a.currencies = p.currencies)
            .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.CURRENCIES)))
            .documentation("The wallets whose balances the header shows, in the order they read. List every "
                    + "wallet this shop prices in and nothing else: a balance a player needs and cannot see is "
                    + "the one thing a storefront must never hide. This is ONE leaf, so authoring it replaces an "
                    + "inherited list whole.")
            .add()
            .appendInherited(new KeyedCodec<>("CategoryOrder", Codec.STRING_ARRAY, false),
                    (a, v) -> a.categoryOrder = v, a -> a.categoryOrder,
                    (a, p) -> a.categoryOrder = p.categoryOrder)
            .documentation("The order the shelves read in. Unauthored sorts them alphabetically, which puts "
                    + "'rare' ahead of 'uncommon'; a category left off the list follows the named ones "
                    + "alphabetically.")
            .add()
            .appendInherited(new KeyedCodec<>("Categories", new InheritMapCodec<>(ContentTextAsset.CODEC), false),
                    (a, v) -> a.categories = v, a -> a.categories, (a, p) -> a.categories = p.categories)
            .documentation("What each shelf is CALLED, keyed by the category's own word, as a localization key in "
                    + "your own lang file. The common shelves already read in words without this; a category you "
                    + "invented reads as the word you wrote it as until you point a TitleKey at a line of yours. "
                    + "Separate from CategoryOrder on purpose: one decides what a shelf says, the other where it "
                    + "sits, and neither needs the other authored. Under Parent this merges per CATEGORY, so a "
                    + "child storefront can rename one shelf and keep the rest.")
            .add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("What a player must already have or have done before this storefront opens for them. "
                    + "An unauthored block asks for nothing; a requirement nothing can answer keeps it shut.")
            .add()
            .appendInherited(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Which worlds this storefront exists in. Unauthored means every world. A world is "
                    + "named by what it is CALLED or by the gameplay config it runs, the same grammar every "
                    + "world-targeted file here uses.")
            .add()
            .appendInherited(new KeyedCodec<>(ContentMeta.KEY, ContentMeta.CODEC, false),
                    (a, v) -> a.meta = v, a -> a.meta, (a, p) -> a.meta = p.meta)
            .documentation(ContentMeta.DOCUMENTATION)
            .add()
            .build();

    public StorefrontAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Can the storefront be opened? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    @Nullable
    public String getIcon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** Where this storefront sorts among others; 0 when unauthored. */
    public int order() {
        return order == null ? 0 : order;
    }

    /** The header's wallets, ids lower-cased, blanks dropped, in authored order. */
    @Nonnull
    public List<String> currencyIds() {
        return lowerList(currencies);
    }

    /** The shelf order, labels lower-cased, blanks dropped, in authored order. */
    @Nonnull
    public List<String> categoryOrder() {
        return lowerList(categoryOrder);
    }

    /**
     * What this storefront calls the {@code categoryId} shelf, or null when it names none. Matched
     * however either was capitalized, the same way every other id here compares.
     */
    @Nullable
    public ContentTextAsset categoryText(@Nullable String categoryId) {
        if (categories == null || categoryId == null || categoryId.isBlank()) {
            return null;
        }
        String wanted = categoryId.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ContentTextAsset> entry : categories.entrySet()) {
            String key = entry.getKey();
            if (key != null && wanted.equals(key.trim().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** What must be true before the storefront opens, or null when it is open to everybody. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** Which worlds this storefront exists in, or null for every world. */
    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    /** The per-namespace extra facts, exactly as authored; empty when the file carried none. */
    @Nonnull
    public Map<String, JsonElement> metaOrEmpty() {
        return ContentMeta.orEmpty(meta);
    }

    @Nonnull
    private static List<String> lowerList(@Nullable String[] values) {
        if (values == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.length);
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
