package com.ziggfreed.common.loot.stamp;

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
 * A named, reusable set of stat-roll outcomes: {@code Server/ZiggfreedCommon/RollPools/<Name>.json},
 * body {@code { "Entries": [ <StatRollEntry>, ... ] }}. The pool's id is the FILENAME, lower-cased.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/RollPools/WeaponStats.json
 * {
 *   "Entries": [
 *     { "Stat": "Damage",     "Points": { "Min": 2, "Max": 6 }, "Weight": 3 },
 *     { "Stat": "AttackSpeed","Points": { "Min": 1, "Max": 3 } }
 *   ]
 * }
 * }</pre>
 *
 * <p>Name a pool as soon as more than one thing rolls the same outcomes - an anvil, a reward, a
 * chest - and every one of them picks up a retune at once. A single-site set of outcomes is better
 * written inline where it is used.
 *
 * <p>Like every table of this shape, {@code Entries} REPLACES rather than appends when a file
 * carries a {@code Parent}: a child that authors any entries discards the ones it inherited. Add
 * extra outcomes inline at the consuming site instead.
 */
public final class RollPoolAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, RollPoolAsset>> {

    /** Where these files live, and the id the Asset Editor serves this type's pick list under. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/RollPools";
    public static final String EDITOR_DATASET = "ziggfreedcommon:rollpools";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private StatRollEntry[] entries;
    @Nullable private String stampName;
    @Nullable private String quality;

    public static final AssetBuilderCodec<String, RollPoolAsset> CODEC = AssetBuilderCodec.builder(
                    RollPoolAsset.class,
                    RollPoolAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* display only - the id comes from the filename */ },
                    a -> a.id)
            .documentation("A human-readable label for editors. The pool's id comes from the filename, so "
                    + "changing this changes nothing at runtime.").add()
            .appendInherited(new KeyedCodec<>("StampName", Codec.STRING, false),
                    (a, v) -> a.stampName = v, a -> a.stampName, (a, parent) -> a.stampName = parent.stampName)
            .documentation("A full translation key renaming anything stamped from this pool, handed the item's "
                    + "own name as an {item} argument. Omit to keep the item's own name. A stamp may override "
                    + "this with its own Name.").add()
            .appendInherited(new KeyedCodec<>("Quality", Codec.STRING, false),
                    (a, v) -> a.quality = v, a -> a.quality, (a, parent) -> a.quality = parent.quality)
            .documentation("An ItemQuality asset id giving anything stamped from this pool that rarity. Omit to "
                    + "leave the item's rarity alone. A stamp may override this with its own Quality.").add()
            .appendInherited(new KeyedCodec<>("Entries",
                            new ArrayCodec<>(StatRollEntry.codec(EditorDataSets.FACTORS), StatRollEntry[]::new), false),
                    (a, v) -> a.entries = v, a -> a.entries, (a, parent) -> a.entries = parent.entries)
            .documentation("The candidate outcomes this pool offers. Authoring this in a file with a Parent "
                    + "REPLACES the parent's entries entirely rather than adding to them.").add()
            .build();

    /**
     * The codec for a leaf that accepts EITHER a plain {@code "<poolId>"} reference OR a whole pool
     * written inline (which may carry its own {@code Parent}).
     */
    @Nonnull
    public static final Codec<String> CHILD_ASSET_CODEC =
            new ContainedAssetCodec<>(RollPoolAsset.class, CODEC);

    public RollPoolAsset() {
    }

    /** Java-side construction path; sets the same fields the codec fills. */
    @Nonnull
    public static RollPoolAsset of(@Nonnull String id, @Nullable StatRollEntry[] entries) {
        RollPoolAsset p = new RollPoolAsset();
        p.id = id.toLowerCase(Locale.ROOT);
        p.entries = entries;
        return p;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The rename key this pool applies, or null to keep each item's own name. */
    @Nullable
    public String getName() {
        return stampName;
    }

    /** The ItemQuality id this pool applies, or null to leave rarity alone. */
    @Nullable
    public String getQuality() {
        return quality;
    }

    @Nullable
    public StatRollEntry[] getEntries() {
        return entries;
    }
}
