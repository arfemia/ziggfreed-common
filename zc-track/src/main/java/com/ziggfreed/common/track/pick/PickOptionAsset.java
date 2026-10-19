package com.ziggfreed.common.track.pick;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.JsonTreeCodec;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.track.TrackKey;

/**
 * One option a choice may offer, at {@code Server/ZiggfreedCommon/TrackPickOptions/<Owner>/<Id>.json}, shared by
 * every pick whose groups name it. The FILE NAME is the id. Its {@code Grants} are a template: each {@code {Name}} in
 * them is filled from the naming pick's {@code Vars} (a group's own over the pick's) by the library's one
 * substitution contract (zc-progression's {@code GeneratorCore}), so one option file serves every weapon skill:
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "yourmod.option.edge" },
 *   "Draw": "Line",
 *   "Grants": [ { "Kind": "Stat", "Stat": "DamageBonus_{Cause}", "Add": "{Step}" } ] }
 * }</pre>
 *
 * <p>{@code Draw} is the part of an offer it fills (a deck reserves room for some, such as a knack slot, and offers
 * others only at its foe levels), {@code Bag} how often a deck brings it round and {@code Turn} where in each round,
 * {@code From} and {@code To} the levels it may be offered at. Every leaf inherits through {@code Parent}; a server
 * owner retunes an option in {@code mods/ziggfreedcommon/track-pick-options.json}.
 */
public final class PickOptionAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, PickOptionAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackPickOptions";

    /** The part of an offer an option fills when it names none. */
    public static final String DEFAULT_DRAW = "Line";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected ContentTextAsset text;
    @Nullable protected String icon;
    @Nullable protected String draw;
    @Nullable protected String bag;
    @Nullable protected Integer turn;
    @Nullable protected Integer from;
    @Nullable protected Integer to;
    @Nullable protected JsonElement grants;

    public static final AssetBuilderCodec<String, PickOptionAsset> CODEC = AssetBuilderCodec.builder(
                    PickOptionAsset.class,
                    PickOptionAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this option exists on this server; unauthored means true. False keeps it out of "
                    + "every offer, and a player who chose it keeps the choice but not its grants.").add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the option is called (TitleKey) and what it does in plain words (FlavorKey), as "
                    + "localization keys in your own lang file. Without a TitleKey it is named by its id.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the option.").add()
            .appendInherited(new KeyedCodec<>("Draw", Codec.STRING, false),
                    (a, v) -> a.draw = v, a -> a.draw, (a, p) -> a.draw = p.draw)
            .metadata(EditorSchema.defaultValue(DEFAULT_DRAW))
            .documentation("The part of an offer this option fills, a word the deck reads: Line when unauthored, "
                    + "Knack for the deck's knack slot, Foe for its foe levels, or any word a deck names.").add()
            .appendInherited(new KeyedCodec<>("Bag", Codec.STRING, false),
                    (a, v) -> a.bag = v, a -> a.bag, (a, p) -> a.bag = p.bag)
            .documentation("How often a deck brings this option round, a word its Bag names (Often, Sometimes). "
                    + "Unauthored, it may be offered at every level it is open.").add()
            .appendInherited(new KeyedCodec<>("Turn", Codec.INTEGER, false),
                    (a, v) -> a.turn = v, a -> a.turn, (a, p) -> a.turn = p.turn)
            .documentation("Which slot of each round its Bag word sets this option comes due at, counted from 1: "
                    + "with a deck's Bag { \"Often\": 2 }, Turn 1 brings an Often option at a group's first, third "
                    + "and fifth slots, Turn 2 at its second, fourth and sixth. Unauthored, the options naming one "
                    + "Bag word take turns in the order the group lists them. Read only with a Bag word.").add()
            .appendInherited(new KeyedCodec<>("From", Codec.INTEGER, false),
                    (a, v) -> a.from = v, a -> a.from, (a, p) -> a.from = p.from)
            .metadata(EditorSchema.defaultValue(1L))
            .documentation("The first level this option may be offered at. 1 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("To", Codec.INTEGER, false),
                    (a, v) -> a.to = v, a -> a.to, (a, p) -> a.to = p.to)
            .documentation("The last level this option may be offered at. Leave it out for no end.").add()
            .appendInherited(new KeyedCodec<>("Grants", JsonTreeCodec.array(), false),
                    (a, v) -> a.grants = v, a -> a.grants, (a, p) -> a.grants = p.grants)
            .documentation("What taking this option grants, each time it is taken: grant entries with their Kind "
                    + "(Stat raises a native stat), in which {Name} is filled from the naming pick's Vars. A value "
                    + "that is exactly one {Name} takes that Var's own type, so \"Add\": \"{Step}\" reads as a "
                    + "number. A {Name} no Var fills keeps the option out of that pick, reported.").add()
            .build();

    public PickOptionAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The localization key the option is named by, or null. */
    @Nullable
    public String titleKey() {
        return text == null ? null : TrackPickAsset.blankToNull(text.getTitleKey());
    }

    @Nullable
    public String icon() {
        return TrackPickAsset.blankToNull(icon);
    }

    /** The part of an offer it fills, lower-cased ({@code line} unauthored). */
    @Nonnull
    public String draw() {
        String own = TrackKey.normalize(draw);
        return own.isEmpty() ? TrackKey.normalize(DEFAULT_DRAW) : own;
    }

    /** The deck's word for how often it comes round, lower-cased, or null for every level. */
    @Nullable
    public String bag() {
        String own = TrackKey.normalize(bag);
        return own.isEmpty() ? null : own;
    }

    /** Its authored turn in each round of its Bag word, from 1; 0 when unauthored (it takes its turn in order). */
    public int turn() {
        return turn == null ? 0 : Math.max(0, turn);
    }

    public int from() {
        return from == null ? 1 : Math.max(1, from);
    }

    public int to() {
        return to == null ? Integer.MAX_VALUE : to;
    }

    /** The {@code Grants} template as written, or null. */
    @Nullable
    public JsonElement grantsTemplate() {
        return grants;
    }
}
