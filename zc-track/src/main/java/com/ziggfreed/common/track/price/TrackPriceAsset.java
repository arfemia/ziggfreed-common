package com.ziggfreed.common.track.price;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One row of the price table XP rewards are priced from, at {@code Server/ZiggfreedCommon/TrackPrices/<Id>.json}: how
 * many minutes of play a reward of this kind pays (R618), and at which level those minutes are priced. A Track reward
 * or a Track lamp names it by id ({@code "Price": "Quest"}), so every quest of a server pays from one row an owner
 * retunes in one place, and a reward may still write its own {@code Minutes}, {@code Level} or {@code MaxLevel} over
 * the row's.
 *
 * <pre>{@code
 * { "Minutes": 15 }                       // a quest: 15 minutes of play at the player's level
 * { "Minutes": 20, "MaxLevel": 30 }       // a repeat clear: priced at the player's level, at most level 30's
 * { "Minutes": 15, "Level": 40 }          // one-off content made for level 40: the same for everyone
 * }</pre>
 *
 * <p>A minute is a sixtieth of the {@code Pace} band (on the paid track's curve) the pricing level stands in; the
 * reward is priced when it is earned and kept in its Claim as the amount it came to, paid past every boost. The file
 * name is the id; a pack ships a row of its own beside the library's or retunes one by its id, and the owner file
 * {@code mods/ziggfreedcommon/track-prices.json} wins over both (the owner menu's XP Prices screen writes it).
 */
public final class TrackPriceAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackPriceAsset>> {

    /** The store's content path. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackPrices";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Double minutes;
    @Nullable protected Integer level;
    @Nullable protected Integer maxLevel;

    public static final AssetBuilderCodec<String, TrackPriceAsset> CODEC = AssetBuilderCodec.builder(
                    TrackPriceAsset.class,
                    TrackPriceAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Minutes", Codec.DOUBLE, false),
                    (a, v) -> a.minutes = v, a -> a.minutes, (a, p) -> a.minutes = p.minutes)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("How many minutes of focused play a reward of this price pays, at the Pace of the paid "
                    + "track's curve. 0 or unauthored pays nothing.").add()
            .appendInherited(new KeyedCodec<>("Level", Codec.INTEGER, false),
                    (a, v) -> a.level = v, a -> a.level, (a, p) -> a.level = p.level)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Price the minutes at this level, whoever earns them: one-off content made for that "
                    + "level pays everyone the same. 0 or unauthored prices them at the player's own level.").add()
            .appendInherited(new KeyedCodec<>("MaxLevel", Codec.INTEGER, false),
                    (a, v) -> a.maxLevel = v, a -> a.maxLevel, (a, p) -> a.maxLevel = p.maxLevel)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("When priced at the player's own level, never price above this level, so easy repeatable "
                    + "content stops paying more past it. 0 or unauthored sets no limit.").add()
            .build();

    public TrackPriceAsset() {
    }

    /** Java-side factory (a test); sets the same fields the codec fills. */
    @Nonnull
    public static TrackPriceAsset of(@Nonnull String id, @Nullable Double minutes, @Nullable Integer level,
            @Nullable Integer maxLevel) {
        TrackPriceAsset asset = new TrackPriceAsset();
        asset.id = id;
        asset.minutes = minutes;
        asset.level = level;
        asset.maxLevel = maxLevel;
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The minutes it pays, 0 or more. */
    public double minutes() {
        return minutes == null || !Double.isFinite(minutes) ? 0.0 : Math.max(0.0, minutes);
    }

    /** The fixed pricing level, 0 for the player's own. */
    public int level() {
        return level == null ? 0 : Math.max(0, level);
    }

    /** The highest level the player's own is priced at, 0 for no limit. */
    public int maxLevel() {
        return maxLevel == null ? 0 : Math.max(0, maxLevel);
    }
}
