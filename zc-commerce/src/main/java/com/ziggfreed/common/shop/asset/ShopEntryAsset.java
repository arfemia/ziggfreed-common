package com.ziggfreed.common.shop.asset;

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
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.commerce.asset.CommerceEditorDataSets;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * One OFFER, at {@code Server/ZiggfreedCommon/ShopEntries/<ns>/<Id>.json}. The FILE NAME is the
 * offer id.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "shop.boost_mining.title", "FlavorKey": "shop.boost_mining.desc" },
 *   "Icon": "Tool_Pickaxe_Crude",
 *   "Shop": "General",
 *   "Listing": { "Category": "boosts", "SortOrder": 20 },
 *   "Cost": { "Currencies": { "Bounty_Token": 150 } },
 *   "Limits": { "Daily": 3 },
 *   "Requires": { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_Level_MINING", "Min": 1 } ] },
 *   "Rewards": [ { "Kind": "Mmo_Boost_Token",
 *                  "Params": { "Skill": "MINING", "Multiplier": "3.0", "DurationMinutes": "20" } } ] }
 * }</pre>
 *
 * <p>An offer is a PRICE in exchange for a REWARD, and both are the library's shared vocabularies -
 * the same price group a reroll uses, and the same reward entries a quest turn-in pays out. So a
 * reward kind a mod adds works in a shop the day it is registered, with no new code here.
 *
 * <p><b>{@code Requires} is the ONE gate block</b>, shared with quests and achievements: factor
 * bounds, a permission, finished quests, or a registered requirement kind. There is no shop-only
 * requirement vocabulary, so a gate an author already knows how to write means the same here.
 *
 * <p><b>{@code Pool} is what makes an offer part of a rotating shelf</b> rather than always on the
 * page. Leave it out for the standing catalogue; author it with a shelf id, the tier its slots
 * filter on, and a weight to bias the draw.
 *
 * <p><b>{@code Limits} are two independent ceilings</b>: {@code Daily} resets on the server's own
 * clock, {@code Total} never does. Author either, both, or neither.
 *
 * <p>A ladder of near-identical offers - the same packet at three sizes, once per skill - is better
 * written as ONE {@code Abstract} offer plus a generator
 * ({@code Server/ZiggfreedCommon/ShopEntryGenerators/}) than as thirty files to keep in step.
 */
public final class ShopEntryAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ShopEntryAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/ShopEntries";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Boolean isAbstract;
    @Nullable private ContentTextAsset text;
    @Nullable private Listing listing;
    @Nullable private String icon;
    @Nullable private String shop;
    @Nullable private CostAsset cost;
    @Nullable private Limits limits;
    @Nullable private PoolMembership pool;
    @Nullable private GateSpec requires;
    @Nullable private RewardEntryAsset[] rewards;
    @Nullable private Map<String, JsonElement> meta;

    public static final AssetBuilderCodec<String, ShopEntryAsset> CODEC = AssetBuilderCodec.builder(
                    ShopEntryAsset.class,
                    ShopEntryAsset::new,
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
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the offer is for sale; unauthored means true. Setting false takes it off the "
                    + "page while leaving the file to come back with one edit.")
            .add()
            // The ONE field that deliberately does NOT inherit: a child of a skeleton is a real
            // offer, so inheriting this would take every child of a base off the page too.
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false),
                    (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .documentation("Mark a file that exists only to be inherited from. It stays available as a Parent "
                    + "target and is never for sale, so a shared skeleton needs no price of its own. It never "
                    + "carries down to a child: inheriting from a skeleton makes a real offer.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the player reads, as localization keys. TextArgs is how one written line - 'A "
                    + "packet worth {0} experience' - serves a whole ladder of offers.")
            .add()
            .appendInherited(new KeyedCodec<>("Listing", Listing.CODEC, false),
                    (a, v) -> a.listing = v, a -> a.listing, (a, p) -> a.listing = p.listing)
            .documentation("Which shelf the offer sits on and where it sorts. Chains is how a ladder of "
                    + "near-identical offers is shown as one climbing entry instead of a row of them.")
            .add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(new UIEditor(new UIEditor.Dropdown("hytale:item")))
            .documentation("The item whose picture stands for this offer in the list. Unauthored falls back to "
                    + "whatever the first reward can supply, so an offer handing over an item needs no icon.")
            .add()
            .appendInherited(new KeyedCodec<>("Shop", Codec.STRING, false),
                    (a, v) -> a.shop = v, a -> a.shop, (a, p) -> a.shop = p.shop)
            .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.SHOPS)))
            .documentation("The storefront this offer is sold at, by id. An offer naming a storefront nobody "
                    + "authored is never on sale anywhere, which the audit says at load.")
            .add()
            .appendInherited(new KeyedCodec<>("Cost", CostAsset.CODEC, false),
                    (a, v) -> a.cost = v, a -> a.cost, (a, p) -> a.cost = p.cost)
            .documentation("What the player hands over. An unauthored or empty price is FREE, which is a real "
                    + "answer for a starter offer but worth a second look anywhere else.")
            .add()
            .appendInherited(new KeyedCodec<>("Limits", Limits.CODEC, false),
                    (a, v) -> a.limits = v, a -> a.limits, (a, p) -> a.limits = p.limits)
            .documentation("How often one player may buy it. Unauthored means as often as they can afford it.")
            .add()
            .appendInherited(new KeyedCodec<>("Pool", PoolMembership.CODEC, false),
                    (a, v) -> a.pool = v, a -> a.pool, (a, p) -> a.pool = p.pool)
            .documentation("Author it to make this offer part of a rotating shelf rather than always on the "
                    + "page. Unauthored keeps it in the standing catalogue.")
            .add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("What a player must already have or have done before they may buy it. An unauthored "
                    + "block asks for nothing; a requirement nothing can answer keeps the offer locked, and a "
                    + "locked offer is still SHOWN, so a player can see what to work towards.")
            .add()
            .appendInherited(new KeyedCodec<>("Rewards",
                            new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                    (a, v) -> a.rewards = v, a -> a.rewards, (a, p) -> a.rewards = p.rewards)
            .documentation("What the player gets for the price. This is ONE leaf: author it and an inherited "
                    + "list is replaced whole, omit it and the inherited list carries over. An offer that hands "
                    + "over nothing is reported rather than sold.")
            .add()
            .appendInherited(new KeyedCodec<>(ContentMeta.KEY, ContentMeta.CODEC, false),
                    (a, v) -> a.meta = v, a -> a.meta, (a, p) -> a.meta = p.meta)
            .documentation(ContentMeta.DOCUMENTATION)
            .add()
            .build();

    public ShopEntryAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** For sale? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** A skeleton that exists only to be inherited from, never for sale. */
    public boolean isAbstract() {
        return isAbstract != null && isAbstract;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    @Nullable
    public Listing getListing() {
        return listing;
    }

    @Nullable
    public String getIcon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** The storefront this offer is sold at, lower-cased, or null when it names none. */
    @Nullable
    public String getShop() {
        return shop == null || shop.isBlank() ? null : shop.trim().toLowerCase(Locale.ROOT);
    }

    /** The price, never null, so a caller charges the same way whether or not one was authored. */
    @Nonnull
    public CostAsset costOrFree() {
        return cost == null ? CostAsset.FREE : cost;
    }

    /** The authored price, or null when the offer is free. */
    @Nullable
    public CostAsset getCost() {
        return cost;
    }

    @Nullable
    public Limits getLimits() {
        return limits;
    }

    /** How many one player may buy per day; 0 means no daily limit. */
    public int dailyLimit() {
        return limits == null ? 0 : limits.daily();
    }

    /** How many one player may ever buy; 0 means no lifetime limit. */
    public int totalLimit() {
        return limits == null ? 0 : limits.total();
    }

    @Nullable
    public PoolMembership getPool() {
        return pool;
    }

    /** True when this offer belongs to a rotating shelf rather than the standing catalogue. */
    public boolean isPooled() {
        return pool != null && pool.getId() != null;
    }

    /** What must be true before the offer may be bought, or null when anybody may. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** The authored rewards, in authored order. */
    @Nonnull
    public RewardEntryAsset[] rewardsOrEmpty() {
        return rewards == null ? new RewardEntryAsset[0] : rewards;
    }

    /** The per-namespace extra facts, exactly as authored; empty when the file carried none. */
    @Nonnull
    public Map<String, JsonElement> metaOrEmpty() {
        return ContentMeta.orEmpty(meta);
    }

    // ==================== Listing ====================

    /** Which shelf the offer sits on and where it sorts, in the library's shared listing group. */
    public static final class Listing extends ContentListingAsset {

        public static final BuilderCodec<Listing> CODEC =
                appendLeaves(BuilderCodec.builder(Listing.class, Listing::new)).build();

        public Listing() {
        }

        @Nonnull
        public static Listing of(@Nullable String category, @Nullable Integer sortOrder,
                @Nullable String[] tags) {
            Listing l = new Listing();
            l.category = category;
            l.sortOrder = sortOrder;
            l.tags = tags == null ? null : tags.clone();
            return l;
        }

        /** The shelf label, lower-cased for matching a storefront's CategoryOrder, or null. */
        @Nullable
        public String categoryId() {
            String authored = getCategory();
            return authored == null || authored.isBlank()
                    ? null : authored.trim().toLowerCase(Locale.ROOT);
        }
    }

    // ==================== Limits ====================

    /** How often one player may buy this offer. Two independent ceilings; author either or both. */
    public static final class Limits {

        @Nullable protected Integer daily;
        @Nullable protected Integer total;

        public static final BuilderCodec<Limits> CODEC = BuilderCodec.builder(Limits.class, Limits::new)
                .appendInherited(new KeyedCodec<>("Daily", Codec.INTEGER, false),
                        (o, v) -> o.daily = v, o -> o.daily, (o, p) -> o.daily = p.daily)
                .documentation("How many one player may buy in a day, counted on the server's own clock so "
                        + "everybody's allowance rolls over at the same instant. 0 or unauthored means no daily "
                        + "limit.").add()
                .appendInherited(new KeyedCodec<>("Total", Codec.INTEGER, false),
                        (o, v) -> o.total = v, o -> o.total, (o, p) -> o.total = p.total)
                .documentation("How many one player may EVER buy. 0 or unauthored means no lifetime limit. Use "
                        + "it for a one-off unlock, where a daily allowance would make no sense.").add()
                .build();

        public Limits() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Limits of(@Nullable Integer daily, @Nullable Integer total) {
            Limits l = new Limits();
            l.daily = daily;
            l.total = total;
            return l;
        }

        /** The daily allowance; 0 (unlimited) when unauthored or authored negative. */
        public int daily() {
            return daily == null || daily < 0 ? 0 : daily;
        }

        /** The lifetime allowance; 0 (unlimited) when unauthored or authored negative. */
        public int total() {
            return total == null || total < 0 ? 0 : total;
        }

        /** The authored daily allowance exactly as written, for an audit that must see a bad one. */
        @Nullable
        public Integer getDaily() {
            return daily;
        }

        /** The authored lifetime allowance exactly as written, for an audit. */
        @Nullable
        public Integer getTotal() {
            return total;
        }
    }

    // ==================== PoolMembership ====================

    /**
     * The rotating shelf this offer belongs to, which slot of it it can fill, and how strongly it is
     * drawn.
     *
     * <p>It is one group rather than three loose keys because the three only mean anything together:
     * a tier with no shelf names a slot on nothing, and a weight with no shelf biases a draw that
     * never happens.
     */
    public static final class PoolMembership {

        @Nullable protected String id;
        @Nullable protected String tier;
        @Nullable protected Double weight;

        public static final BuilderCodec<PoolMembership> CODEC =
                BuilderCodec.builder(PoolMembership.class, PoolMembership::new)
                        .appendInherited(new KeyedCodec<>("Id", Codec.STRING, false),
                                (o, v) -> o.id = v, o -> o.id, (o, p) -> o.id = p.id)
                        .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.SHOP_POOLS)))
                        .documentation("The rotating shelf this offer may be drawn onto, by id.").add()
                        .appendInherited(new KeyedCodec<>("Tier", Codec.STRING, false),
                                (o, v) -> o.tier = v, o -> o.tier, (o, p) -> o.tier = p.tier)
                        .documentation("Which of the shelf's slots this offer can fill, matched against that "
                                + "slot's own Tier. Unauthored fits any unslotted draw but no filtered slot, so "
                                + "an offer on a fully slotted shelf needs one.").add()
                        .appendInherited(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                                (o, v) -> o.weight = v, o -> o.weight, (o, p) -> o.weight = p.weight)
                        .metadata(EditorSchema.defaultValue(1.0))
                        .documentation("How strongly this offer is drawn against its rivals for one slot. "
                                + "Unauthored means 1; 2 is twice as likely as a 1. Zero or less would make it "
                                + "undrawable, so it is read as 1 and reported.").add()
                        .build();

        public PoolMembership() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static PoolMembership of(@Nullable String id, @Nullable String tier, @Nullable Double weight) {
            PoolMembership m = new PoolMembership();
            m.id = id;
            m.tier = tier;
            m.weight = weight;
            return m;
        }

        /** The shelf id, lower-cased, or null when the group names none. */
        @Nullable
        public String getId() {
            return id == null || id.isBlank() ? null : id.trim().toLowerCase(Locale.ROOT);
        }

        /** The slot label, lower-cased for matching, or null for "any unslotted draw". */
        @Nullable
        public String getTier() {
            return tier == null || tier.isBlank() ? null : tier.trim().toLowerCase(Locale.ROOT);
        }

        /** The draw weight, at least a hair above zero; 1 when unauthored or authored non-positive. */
        public double weightOrOne() {
            return weight == null || weight <= 0.0 ? 1.0 : weight;
        }

        /** The authored weight exactly as written, for an audit that must see a bad one. */
        @Nullable
        public Double getWeight() {
            return weight;
        }
    }
}
