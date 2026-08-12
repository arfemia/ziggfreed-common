package com.ziggfreed.common.loot;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorDataSets;

/**
 * A named, reusable loot table: {@code Server/ZiggfreedCommon/Lootables/<Name>.json}, body
 * {@code { "Rolls": [ <Roll>, ... ] }}. The table's id is the FILENAME, lower-cased; the
 * {@code Name} key inside the file is display only.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/Lootables/ForestFinds.json
 * {
 *   "Rolls": [
 *     { "Chance": { "Base": 5 }, "Grants": { "Items": [ { "Item": "Coin_Gold" } ] } }
 *   ]
 * }
 * }</pre>
 *
 * <p>Anything that pays out references the table by id, so one table serves many sites and retuning
 * it retunes all of them. A table nobody references is simply dormant, not an error.
 *
 * <h2>Overriding and inheriting</h2>
 *
 * <p>A file carrying {@code "Parent": "<id>"} starts from that table. Be aware that {@code Rolls} is
 * this asset's only content, and it REPLACES wholesale rather than appending: a child that authors
 * any rolls at all discards every roll it inherited. To ADD a roll to a shared table, author it
 * inline beside the reference on the consuming site rather than in a child file.
 *
 * <p>To retune a table someone else shipped, drop a file with the SAME name into your own pack -
 * later packs win by id. Give your own tables distinctive names, because ids are matched on the
 * filename alone across every installed pack.
 */
public final class LootableAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, LootableAsset>> {

    /** Where these files live, and the id the Asset Editor serves this type's pick list under. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Lootables";
    public static final String EDITOR_DATASET = "ziggfreedcommon:lootables";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Roll[] rolls;

    public static final AssetBuilderCodec<String, LootableAsset> CODEC = AssetBuilderCodec.builder(
                    LootableAsset.class,
                    LootableAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* display only - the id comes from the filename */ },
                    a -> a.id)
            .documentation("A human-readable label for editors. The table's id comes from the filename, so "
                    + "changing this changes nothing at runtime.").add()
            .appendInherited(new KeyedCodec<>("Rolls",
                            new ArrayCodec<>(Roll.codec(EditorDataSets.FACTORS), Roll[]::new), false),
                    (a, v) -> a.rolls = v, a -> a.rolls, (a, parent) -> a.rolls = parent.rolls)
            .documentation("The rolls this table contributes. Authoring this in a file with a Parent REPLACES "
                    + "the parent's rolls entirely rather than adding to them.").add()
            .build();

    /**
     * The codec for a leaf that accepts EITHER a plain {@code "<lootableId>"} reference OR a whole
     * table written inline (which may carry its own {@code Parent}). Inline is for a table used at
     * exactly one site; name it as a file the moment a second site wants it.
     */
    @Nonnull
    public static final Codec<String> CHILD_ASSET_CODEC =
            new ContainedAssetCodec<>(LootableAsset.class, CODEC);

    public LootableAsset() {
    }

    /** Java-side construction path; sets the same fields the codec fills. */
    @Nonnull
    public static LootableAsset of(@Nonnull String id, @Nullable Roll[] rolls) {
        LootableAsset a = new LootableAsset();
        a.id = id.toLowerCase(Locale.ROOT);
        a.rolls = rolls;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public Roll[] getRolls() {
        return rolls;
    }
}
