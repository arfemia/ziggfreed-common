package com.ziggfreed.common.currency.asset;

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
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * One WALLET a player can hold, at {@code Server/ZiggfreedCommon/Currencies/<ns>/<Id>.json}. The
 * FILE NAME is the currency id.
 *
 * <pre>{@code
 * // a counter-backed wallet: a number this server keeps for each player
 * { "Icon": "Ingredient_Bar_Gold", "Color": "#ffcc44", "Cap": 0 }
 *
 * // an item-backed wallet: the balance IS how many of the item the player is carrying
 * { "Backing": { "Item": "Ingredient_Life_Essence" }, "Color": "#a7e0a7" }
 * }</pre>
 *
 * <p><b>Backing is the one real choice, and nothing else changes with it.</b> A counter-backed
 * wallet is a number nobody can drop, trade or lose to a full backpack; an item-backed one is
 * carried in the inventory, so it can be given away and it obeys whatever the game already does with
 * items on death. Everything below - the cap, the wear, what it is called - reads the same either
 * way, and nothing that spends a wallet ever has to ask which kind it is.
 *
 * <p><b>What the player reads.</b> Leave {@code Text} out and the name comes from the convention key
 * {@code currency.<id>.name}, or, for an item-backed wallet with no key written, from the backing
 * item's own name in the player's language. Author {@code Text.TitleKey} when the wallet needs a name
 * of its own.
 *
 * <p><b>{@code Cap}, {@code OnDeath} and {@code Decay} are independent economy knobs</b>, each
 * unauthored meaning "no such rule": a wallet with none of them is a permanent balance that only
 * ever goes up when it is earned and down when it is spent. Reach for them to make a currency feel
 * like something that has to be used rather than hoarded.
 *
 * <p><b>A knob only one mod understands goes in {@code Meta}</b>, under that mod's namespace - where
 * a wallet sits on a scoreboard, whether experience converts into it. Nothing here interprets those,
 * and a wallet authored for two mods still loads with one of them installed.
 *
 * <p>To retune a wallet somebody else shipped, override the file by id (a same-named file in a later
 * pack or in the owner layer {@code mods/ziggfreedcommon/currencies.json}), or ship your own with
 * {@code Parent} set to theirs and author only what you change.
 */
public final class CurrencyAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, CurrencyAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Currencies";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private ContentTextAsset text;
    @Nullable private Backing backing;
    @Nullable private String icon;
    @Nullable private String color;
    @Nullable private Long cap;
    @Nullable private OnDeath onDeath;
    @Nullable private Decay decay;
    @Nullable private GateSpec requires;
    @Nullable private Map<String, JsonElement> meta;

    public static final AssetBuilderCodec<String, CurrencyAsset> CODEC = AssetBuilderCodec.builder(
                    CurrencyAsset.class,
                    CurrencyAsset::new,
                    Codec.STRING,
                    // Every reader addresses a wallet lower-cased while the engine's asset key is the
                    // verbatim filename; canonicalizing at the one decode authority keeps getId() the
                    // same string everywhere.
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
            .documentation("Whether this wallet exists at all; unauthored means true. Setting false hides it and "
                    + "stops anything charging in it, while leaving balances players already hold untouched.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the player reads, as localization keys. Leave it out and the name comes from "
                    + "currency.<id>.name, or from the backing item's own name when there is one.")
            .add()
            .appendInherited(new KeyedCodec<>("Backing", Backing.CODEC, false),
                    (a, v) -> a.backing = v, a -> a.backing, (a, p) -> a.backing = p.backing)
            .documentation("Author it to make the balance an inventory count of a real item, which players can "
                    + "carry, trade and lose the way they lose anything else. Leave it out for a plain number "
                    + "this server keeps for each player.")
            .add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(new UIEditor(new UIEditor.Dropdown("hytale:item")))
            .documentation("The item whose picture stands for this wallet wherever a balance or a price is shown. "
                    + "Unauthored falls back to the backing item, so an item-backed wallet needs no icon at all.")
            .add()
            .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                    (a, v) -> a.color = v, a -> a.color, (a, p) -> a.color = p.color)
            .documentation("The wallet's colour as a six-digit hex value (#ffcc44), used for its balance and its "
                    + "prices. Unauthored renders in the surrounding text colour.")
            .add()
            .appendInherited(new KeyedCodec<>("Cap", Codec.LONG, false),
                    (a, v) -> a.cap = v, a -> a.cap, (a, p) -> a.cap = p.cap)
            .documentation("The most a player may hold. 0 or unauthored means no ceiling. A cap is what stops a "
                    + "wallet becoming a number nobody looks at any more, so pair it with something worth "
                    + "spending on.")
            .add()
            .appendInherited(new KeyedCodec<>("OnDeath", OnDeath.CODEC, false),
                    (a, v) -> a.onDeath = v, a -> a.onDeath, (a, p) -> a.onDeath = p.onDeath)
            .documentation("What dying costs. Unauthored means nothing, which is what most wallets want.")
            .add()
            .appendInherited(new KeyedCodec<>("Decay", Decay.CODEC, false),
                    (a, v) -> a.decay = v, a -> a.decay, (a, p) -> a.decay = p.decay)
            .documentation("How fast an untouched balance wears away. Unauthored means it does not.")
            .add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("What a player must already have or have done before this wallet is SHOWN to them at "
                    + "all. Unauthored shows it to everybody. Use it to keep a late-game wallet out of a new "
                    + "player's way, not to stop them earning it.")
            .add()
            .appendInherited(new KeyedCodec<>(ContentMeta.KEY, ContentMeta.CODEC, false),
                    (a, v) -> a.meta = v, a -> a.meta, (a, p) -> a.meta = p.meta)
            .documentation(ContentMeta.DOCUMENTATION)
            .add()
            .build();

    public CurrencyAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** In circulation? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    @Nullable
    public Backing getBacking() {
        return backing;
    }

    /** True when the balance IS an inventory count rather than a number this server keeps. */
    public boolean isItemBacked() {
        return backing != null && backing.getItem() != null;
    }

    /** The backing item id, or null for a counter-backed wallet. */
    @Nullable
    public String backingItemId() {
        return backing == null ? null : backing.getItem();
    }

    /**
     * The item whose picture stands for this wallet: the authored one, else the backing item. Null
     * only when a counter-backed wallet authors no icon, which every render site treats as "no
     * picture" rather than falling back to something arbitrary.
     */
    @Nullable
    public String effectiveIconItemId() {
        String authored = icon == null || icon.isBlank() ? null : icon.trim();
        return authored != null ? authored : backingItemId();
    }

    /** The authored icon exactly as written, or null. */
    @Nullable
    public String getIcon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** The authored colour exactly as written, or null. */
    @Nullable
    public String getColor() {
        return color == null || color.isBlank() ? null : color.trim();
    }

    /** The ceiling; 0 (uncapped) when unauthored or authored negative. */
    public long cap() {
        return cap == null || cap < 0L ? 0L : cap;
    }

    /** The authored ceiling exactly as written, for an audit that must see a bad one. */
    @Nullable
    public Long getCap() {
        return cap;
    }

    @Nullable
    public OnDeath getOnDeath() {
        return onDeath;
    }

    @Nullable
    public Decay getDecay() {
        return decay;
    }

    /** The share of the balance lost on death, 0 to 1; 0 when unauthored. */
    public double lossOnDeath() {
        return onDeath == null ? 0.0 : onDeath.lossPercent();
    }

    /** The share of the balance worn away per offline day, 0 to 1; 0 when unauthored. */
    public double decayPerDay() {
        return decay == null ? 0.0 : decay.perDayPercent();
    }

    /** What must be true before a player sees this wallet, or null when everybody does. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** The per-namespace extra facts, exactly as authored; empty when the file carried none. */
    @Nonnull
    public Map<String, JsonElement> metaOrEmpty() {
        return ContentMeta.orEmpty(meta);
    }

    // ==================== Backing ====================

    /** What the balance really IS, when it is a real item rather than a number. */
    public static final class Backing {

        @Nullable protected String item;

        public static final BuilderCodec<Backing> CODEC = BuilderCodec.builder(Backing.class, Backing::new)
                .appendInherited(new KeyedCodec<>("Item", Codec.STRING, false),
                        (o, v) -> o.item = v, o -> o.item, (o, p) -> o.item = p.item)
                .metadata(new UIEditor(new UIEditor.Dropdown("hytale:item")))
                .documentation("The item id the balance is counted in. The player's balance is however many of it "
                        + "they are carrying, so it can be dropped, traded and stored like anything else, and no "
                        + "separate number is kept anywhere.").add()
                .build();

        public Backing() {
        }

        /** Java-side factory; sets the same field the codec fills. */
        @Nonnull
        public static Backing of(@Nullable String item) {
            Backing b = new Backing();
            b.item = item;
            return b;
        }

        /** The backing item id, or null when the group names none. */
        @Nullable
        public String getItem() {
            return item == null || item.isBlank() ? null : item.trim();
        }
    }

    // ==================== OnDeath ====================

    /** What dying costs a player who holds this wallet. */
    public static final class OnDeath {

        @Nullable protected Double lossPercent;

        public static final BuilderCodec<OnDeath> CODEC = BuilderCodec.builder(OnDeath.class, OnDeath::new)
                .appendInherited(new KeyedCodec<>("LossPercent", Codec.DOUBLE, false),
                        (o, v) -> o.lossPercent = v, o -> o.lossPercent,
                        (o, p) -> o.lossPercent = p.lossPercent)
                .documentation("The SHARE of the balance lost on death, between 0 and 1 - 0.1 takes a tenth, 1 "
                        + "takes the lot. Unauthored means nothing is lost. An item-backed wallet is already "
                        + "subject to whatever the game does with items on death, so authoring this on one takes "
                        + "a second bite.").add()
                .build();

        public OnDeath() {
        }

        /** Java-side factory; sets the same field the codec fills. */
        @Nonnull
        public static OnDeath of(@Nullable Double lossPercent) {
            OnDeath o = new OnDeath();
            o.lossPercent = lossPercent;
            return o;
        }

        /** The authored share exactly as written, for an audit that must see a bad one. */
        @Nullable
        public Double getLossPercent() {
            return lossPercent;
        }

        /** The share lost, clamped to 0 to 1. */
        public double lossPercent() {
            return clampShare(lossPercent);
        }
    }

    // ==================== Decay ====================

    /** How fast an untouched balance wears away. */
    public static final class Decay {

        @Nullable protected Double perDayPercent;

        public static final BuilderCodec<Decay> CODEC = BuilderCodec.builder(Decay.class, Decay::new)
                .appendInherited(new KeyedCodec<>("PerDayPercent", Codec.DOUBLE, false),
                        (o, v) -> o.perDayPercent = v, o -> o.perDayPercent,
                        (o, p) -> o.perDayPercent = p.perDayPercent)
                .documentation("The SHARE of the balance worn away for each day a player is away, between 0 and "
                        + "1, compounded over however many days that was. Unauthored means it never decays. Keep "
                        + "it small: a tenth a day halves a hoard inside a week.").add()
                .build();

        public Decay() {
        }

        /** Java-side factory; sets the same field the codec fills. */
        @Nonnull
        public static Decay of(@Nullable Double perDayPercent) {
            Decay d = new Decay();
            d.perDayPercent = perDayPercent;
            return d;
        }

        /** The authored share exactly as written, for an audit that must see a bad one. */
        @Nullable
        public Double getPerDayPercent() {
            return perDayPercent;
        }

        /** The share worn away per day, clamped to 0 to 1. */
        public double perDayPercent() {
            return clampShare(perDayPercent);
        }
    }

    /** A share leaf read as a fraction of the balance: null is none, and it never leaves 0 to 1. */
    private static double clampShare(@Nullable Double authored) {
        if (authored == null || authored.isNaN() || authored < 0.0) {
            return 0.0;
        }
        return authored > 1.0 ? 1.0 : authored;
    }
}
