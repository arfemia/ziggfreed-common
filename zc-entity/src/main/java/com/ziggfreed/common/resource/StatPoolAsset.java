package com.ziggfreed.common.resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * The two numbers a server owner tunes on a pool a cost spends: its maximum and how fast it refills. One entry of
 * {@code mods/ziggfreedcommon/stat-pools.json}, keyed by the stat's own id, over the values the stat's native file
 * ships ({@link StatPools}, which reads those off the loaded stat and writes the owner's back onto it):
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/stat-pools.json
 * { "Energy": { "Max": 120, "RegenPerSecond": 12 } }
 * }</pre>
 *
 * <p>Not a store of files: a pool is the native stat itself ({@code Server/Entity/Stats/<Id>.json}, tagged
 * {@code ZigOwnerPool}), and this is only the owner's word over it. Both leaves are nullable, so an entry that writes
 * one keeps the stat's own other.
 */
public final class StatPoolAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, StatPoolAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Double max;
    @Nullable private Double regenPerSecond;

    public static final AssetBuilderCodec<String, StatPoolAsset> CODEC = AssetBuilderCodec.builder(
                    StatPoolAsset.class,
                    StatPoolAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                    (a, v) -> a.max = v, a -> a.max, (a, p) -> a.max = p.max)
            .documentation("The most the pool holds, the stat's own Max. A carrier that started full starts full "
                    + "at the new maximum. Left out, the stat's file stands.")
            .add()
            .appendInherited(new KeyedCodec<>("RegenPerSecond", Codec.DOUBLE, false),
                    (a, v) -> a.regenPerSecond = v, a -> a.regenPerSecond, (a, p) -> a.regenPerSecond = p.regenPerSecond)
            .documentation("Points the pool refills each second, through the first Regenerating rule of the stat's "
                    + "file (its Amount over its Interval; the rule's conditions stand). 0 stops the refill. Left out, "
                    + "the stat's file stands.")
            .add()
            .build();

    public StatPoolAsset() {
    }

    /** A pool's values as a stat's file ships them, for the layer under the owner's. */
    @Nonnull
    static StatPoolAsset of(@Nonnull String id, double max, double regenPerSecond) {
        StatPoolAsset pool = new StatPoolAsset();
        pool.id = id;
        // A data record as a decoded file carries one, so the codec encodes this value as it encodes any entry.
        pool.data = new AssetExtraInfo.Data(StatPoolAsset.class, id, null);
        pool.max = max;
        pool.regenPerSecond = regenPerSecond;
        return pool;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The maximum as written, or null to keep the stat's own. */
    @Nullable
    public Double max() {
        return max == null || !Double.isFinite(max) ? null : max;
    }

    /** The refill a second as written, or null to keep the stat's own. */
    @Nullable
    public Double regenPerSecond() {
        return regenPerSecond == null || !Double.isFinite(regenPerSecond) ? null : regenPerSecond;
    }
}
