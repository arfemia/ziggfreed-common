package com.ziggfreed.common.quest.asset;

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
 * Writes a FAMILY of quests from one file, at
 * {@code Server/ZiggfreedCommon/QuestGenerators/<id>.json}: "the same quest, once per ore, once per
 * tier" without twenty near-identical files to keep in step.
 *
 * <pre>{@code
 * { "Base": "gather_base",
 *   "IdPattern": "gather_{material}_{tier}",
 *   "ForEach": [ { "Token": "material", "Values": ["copper", "iron"] },
 *                { "Token": "tier",     "Values": [ {"tier": 1, "amount": 10},
 *                                                   {"tier": 2, "amount": 25} ] } ],
 *   "Child": {
 *     "Text": { "TitleKey": "quest.gather.{material}.t{tier}.title" },
 *     "Objectives": { "collect": { "Target": "{material}_Ore", "Amount": "{amount}" } } } }
 * }</pre>
 *
 * <p><b>It writes ordinary child quests, nothing more.</b> Each combination becomes a quest file's
 * worth of JSON with {@code Parent} set to {@code Base}, resolved by exactly the same inheritance a
 * hand-written child gets. So a generated quest can do anything a hand-written one can, behaves
 * identically, and can be replaced by a hand-written file of the same id the day it needs to be
 * special.
 *
 * <p><b>Axes multiply.</b> Two axes of three values produce nine quests. Bind several tokens on one
 * row (the object form above) whenever the values belong together, so "tier 2 asks for 25" is
 * stated once instead of being reconstructed from two independent axes.
 *
 * <p><b>Substitution is textual and applies everywhere</b> in {@code Child}: every string value,
 * every object KEY, and {@code IdPattern}. A value that is exactly one token keeps that token's
 * type, so {@code "Amount": "{amount}"} lands as a number. A token left unreplaced is reported by
 * the validator rather than shipped, because a quest id with a brace in it is nobody's intention.
 */
public final class QuestGeneratorAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, QuestGeneratorAsset>>, GeneratorSpec {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private String base;
    @Nullable private String idPattern;
    @Nullable private GeneratorAxisAsset[] forEach;
    @Nullable private JsonElement child;

    public static final AssetBuilderCodec<String, QuestGeneratorAsset> CODEC = AssetBuilderCodec.builder(
                    QuestGeneratorAsset.class,
                    QuestGeneratorAsset::new,
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
            .documentation("The quest id every child inherits from. Author it as a quest file with Abstract set, "
                    + "carrying everything the family shares.")
            .add()
            .appendInherited(new KeyedCodec<>("IdPattern", Codec.STRING, false),
                    (a, v) -> a.idPattern = v, a -> a.idPattern, (a, p) -> a.idPattern = p.idPattern)
            .documentation("How each child's id is spelled, with {token} placeholders. Include enough tokens to "
                    + "keep every combination distinct, or two children collide and only one survives.")
            .add()
            .appendInherited(new KeyedCodec<>("ForEach",
                            new ArrayCodec<>(GeneratorAxisAsset.CODEC, GeneratorAxisAsset[]::new), false),
                    (a, v) -> a.forEach = v, a -> a.forEach, (a, p) -> a.forEach = p.forEach)
            .documentation("The axes to walk. Several axes multiply, so keep the count small and bind related "
                    + "values on one row instead of adding an axis for each.")
            .add()
            .appendInherited(new KeyedCodec<>("Child", JsonTreeCodec.object(), false),
                    (a, v) -> a.child = v, a -> a.child, (a, p) -> a.child = p.child)
            .documentation("The quest body to write for each combination: a partial quest file, in the same shape "
                    + "as a hand-written one, with {token} placeholders wherever the values differ.")
            .add()
            .build();

    public QuestGeneratorAsset() {
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

    /** The quest id every child inherits from, lower-cased; null when unauthored. */
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
