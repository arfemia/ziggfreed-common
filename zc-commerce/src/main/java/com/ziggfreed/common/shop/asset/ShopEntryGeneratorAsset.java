package com.ziggfreed.common.shop.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.codec.JsonTreeCodec;
import com.ziggfreed.common.progress.asset.GeneratorAxisAsset;
import com.ziggfreed.common.progress.asset.GeneratorSpec;

/**
 * Writes a FAMILY of offers from one file, at
 * {@code Server/ZiggfreedCommon/ShopEntryGenerators/<ns>/<Id>.json}: "the same packet, once per
 * skill, at three sizes" without ninety near-identical files to keep in step.
 *
 * <pre>{@code
 * { "Base": "Xp_Packet",
 *   "IdPattern": "shop_xp_{tier}_{skill}",
 *   "ForEach": [
 *     { "Token": "skill", "Source": "yourmod:skills" },
 *     { "Token": "tier",  "Values": [
 *         { "tier": "lesser",  "tokens": 75,  "essence": 30,  "xp": 1500,  "minLevel": 1,  "order": 40 },
 *         { "tier": "greater", "tokens": 165, "essence": 65,  "xp": 7500,  "minLevel": 30, "order": 42 } ] } ],
 *   "Child": {
 *     "Cost": { "Currencies": { "Bounty_Token": "{tokens}", "Life_Essence": "{essence}" } },
 *     "Listing": { "Category": "conversion", "SortOrder": "{order}",
 *                  "Chains": [ { "Id": "{skill}", "Tier": 1 } ] },
 *     "Requires": { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_Level_{skill}",
 *                                  "Min": "{minLevel}" } ] },
 *     "Rewards": [ { "Kind": "Mmo_Xp", "Params": { "Skill": "{skill}", "Amount": "{xp}" } } ] } }
 * }</pre>
 *
 * <p><b>It writes ordinary child offers, nothing more.</b> Each combination becomes an offer file's
 * worth of JSON with {@code Parent} set to {@code Base}, resolved by exactly the same inheritance a
 * hand-written child gets. So a generated offer can do anything a hand-written one can, and can be
 * replaced by a hand-written file of the same id the day it needs to be special.
 *
 * <p><b>Axes multiply.</b> Two axes of three values produce nine offers. Bind several tokens on one
 * row (the object form above) whenever the values belong together, so "the greater packet costs 165
 * and wants level 30" is stated once instead of reconstructed from three independent axes.
 *
 * <p><b>Substitution is textual and applies everywhere</b> in {@code Child}: every string value,
 * every object KEY, and {@code IdPattern}. Substituting a KEY is what lets a per-skill requirement
 * name its own stat channel ({@code "MMO_Level_{skill}"}) with no workaround. A value that is
 * exactly one token keeps that token's type, so {@code "Min": "{minLevel}"} lands as a number. A
 * token left unresolved is reported and that one offer is skipped rather than shipped half-written.
 *
 * <p>{@code Source} names a list some mod enumerates - every skill, every ore - so a generator
 * written once stays right as that list grows. It is the SAME registered vocabulary the quest
 * generators read, registered once by whichever mod owns the list.
 */
public final class ShopEntryGeneratorAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ShopEntryGeneratorAsset>>, GeneratorSpec {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/ShopEntryGenerators";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private String base;
    @Nullable private String idPattern;
    @Nullable private GeneratorAxisAsset[] forEach;
    @Nullable private JsonElement child;

    public static final AssetBuilderCodec<String, ShopEntryGeneratorAsset> CODEC = AssetBuilderCodec.builder(
                    ShopEntryGeneratorAsset.class,
                    ShopEntryGeneratorAsset::new,
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
            .documentation("Whether this generator runs at all; unauthored means true. Set false to stop a whole "
                    + "family appearing without deleting the file.")
            .add()
            .appendInherited(new KeyedCodec<>("Base", Codec.STRING, false),
                    (a, v) -> a.base = v, a -> a.base, (a, p) -> a.base = p.base)
            .documentation("The offer id every child inherits from. Author it as an offer file with Abstract set, "
                    + "carrying everything the family shares - the storefront, the shelf, the shape of the price.")
            .add()
            .appendInherited(new KeyedCodec<>("IdPattern", Codec.STRING, false),
                    (a, v) -> a.idPattern = v, a -> a.idPattern, (a, p) -> a.idPattern = p.idPattern)
            .documentation("How each child's id is spelled, with {token} placeholders. Include enough tokens to "
                    + "keep every combination distinct, or two children collide and only one survives. An id is "
                    + "what a player's purchase count is filed under, so changing one starts that count over.")
            .add()
            .appendInherited(new KeyedCodec<>("ForEach",
                            new ArrayCodec<>(GeneratorAxisAsset.CODEC, GeneratorAxisAsset[]::new), false),
                    (a, v) -> a.forEach = v, a -> a.forEach, (a, p) -> a.forEach = p.forEach)
            .documentation("The axes to walk. Several axes multiply, so keep the count small and bind related "
                    + "values on one row instead of adding an axis for each.")
            .add()
            .appendInherited(new KeyedCodec<>("Child", JsonTreeCodec.object(), false),
                    (a, v) -> a.child = v, a -> a.child, (a, p) -> a.child = p.child)
            .documentation("The offer body to write for each combination: a partial offer file, in the same "
                    + "shape as a hand-written one, with {token} placeholders wherever the values differ.")
            .add()
            .build();

    public ShopEntryGeneratorAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    @Nonnull
    public String generatorId() {
        return id == null ? "" : id;
    }

    /** Does this generator run? Unauthored means true. */
    @Override
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The offer id every child inherits from, lower-cased; null when unauthored. */
    @Override
    @Nullable
    public String getBase() {
        return base == null || base.isBlank() ? null : base.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    @Nullable
    public String getIdPattern() {
        return idPattern;
    }

    @Override
    @Nonnull
    public GeneratorAxisAsset[] axesOrEmpty() {
        return forEach == null ? new GeneratorAxisAsset[0] : forEach;
    }

    /** The authored child body, or null when the generator writes nothing. */
    @Override
    @Nullable
    public JsonObject getChild() {
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
    }
}
