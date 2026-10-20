package com.ziggfreed.common.track.pick;

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
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.JsonTreeCodec;
import com.ziggfreed.common.progress.asset.GeneratorAxisAsset;
import com.ziggfreed.common.progress.asset.GeneratorSpec;

/**
 * Writes a FAMILY of picks from one file, at {@code Server/ZiggfreedCommon/TrackPickGenerators/<Owner>/<Id>.json}:
 * the node every tree repeats (one per skill), a branch's rows of one stat each, a choice of one face per foe
 * family, without a file per pick to keep in step.
 *
 * <pre>{@code
 * { "Base": "Yours_Tree_{Track}",
 *   "IdPattern": "Yours_{Track}_Hardy",
 *   "ForEach": [ { "Token": "Track", "Values": [ "Swords", "Axes", "Mining" ] } ],
 *   "Child": {
 *     "Text": { "TitleKey": "yourmod.tree.hardy", "FlavorKey": "yourmod.tree.hardy.desc",
 *               "TextArgs": { "Flavor": [ "@num:2", "@key:yourmod.skill.{Track}" ] } },
 *     "Order": 1, "Cost": { "Points": 1 },
 *     "Grants": [ { "Kind": "Stat", "Stat": "Stamina", "Add": 2 } ] } }
 * }</pre>
 *
 * <p><b>It writes ordinary picks, nothing more</b> (zc-progression's {@code GeneratorCore}, the one expander every
 * family-writing store shares): each combination becomes a pick file's worth of JSON with {@code Parent} set to its
 * {@code Base}, decoded against that pick by the same codec a hand-written child goes through. {@code Base} takes
 * tokens too, so each row sits under its own skeleton (a tree's own {@code Parent}, which one file switches off). The
 * ids are ordinary pick ids: a hand-written file of the same id replaces the generated pick (generated picks fold
 * first), and an owner retunes one in {@code track-picks.json} like any other.
 *
 * <p><b>One key per family, never one per pick.</b> A generated pick's {@code Text} names the family's keys and
 * fills their slots from its row through {@code TextArgs} ({@code @key:} a lang key's own line, {@code @item:} an
 * item's name, {@code @num:} a typed number), so the family's lang file gains one name and one description.
 *
 * <p><b>Axes multiply.</b> Two axes of three values write nine picks; bind values that belong together on one row
 * (the object form) rather than adding an axis for each. A value that is exactly one token keeps its type, so
 * {@code "Add": "{Amount}"} lands as a number.
 */
public final class TrackPickGeneratorAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackPickGeneratorAsset>>, GeneratorSpec {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackPickGenerators";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private String base;
    @Nullable private String idPattern;
    @Nullable private GeneratorAxisAsset[] forEach;
    @Nullable private JsonElement child;

    public static final AssetBuilderCodec<String, TrackPickGeneratorAsset> CODEC = AssetBuilderCodec.builder(
                    TrackPickGeneratorAsset.class,
                    TrackPickGeneratorAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this generator writes its picks at all; unauthored means true. Set false to take "
                    + "the whole family away without deleting the file.").add()
            .appendInherited(new KeyedCodec<>("Base", Codec.STRING, false),
                    (a, v) -> a.base = v, a -> a.base, (a, p) -> a.base = p.base)
            .documentation("The pick every generated pick inherits from, by id, usually a skeleton (Abstract) "
                    + "carrying what the family shares: its track, its branch. It takes {token} placeholders like "
                    + "IdPattern, so each row may sit under its own skeleton (Yours_Tree_{Track}). A Base nobody "
                    + "ships writes nothing.").add()
            .appendInherited(new KeyedCodec<>("IdPattern", Codec.STRING, false),
                    (a, v) -> a.idPattern = v, a -> a.idPattern, (a, p) -> a.idPattern = p.idPattern)
            .documentation("How each generated pick's id is spelled, with {token} placeholders. Include enough tokens "
                    + "to keep every combination distinct. A pick id is what a player's record keeps a take under, "
                    + "so changing the pattern on a live server starts those picks over.").add()
            .appendInherited(new KeyedCodec<>("ForEach",
                            new ArrayCodec<>(GeneratorAxisAsset.CODEC, GeneratorAxisAsset[]::new), false),
                    (a, v) -> a.forEach = v, a -> a.forEach, (a, p) -> a.forEach = p.forEach)
            .documentation("The axes to walk. Several axes multiply, so bind values that belong together on one row "
                    + "instead of adding an axis for each.").add()
            .appendInherited(new KeyedCodec<>("Child", JsonTreeCodec.object(), false),
                    (a, v) -> a.child = v, a -> a.child, (a, p) -> a.child = p.child)
            .documentation("The pick body to write for each combination: a partial pick file in the same shape as "
                    + "a hand-written one, with {token} placeholders wherever the rows differ.").add()
            .build();

    public TrackPickGeneratorAsset() {
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

    /** Does this generator write its picks? Unauthored means true. */
    @Override
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /**
     * The Base as written, trimmed, its tokens unfilled (the core fills and lower-cases it per row, so a token
     * keeps its own spelling); null when unauthored.
     */
    @Override
    @Nullable
    public String getBase() {
        return base == null || base.isBlank() ? null : base.trim();
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
