package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * A pack-authorable, mod-agnostic score-tiered reward LOOT TABLE, loaded from a consumer's
 * {@code Server/<Mod>/LootTables/*.json} (the path is supplied by the consumer at register time, so common
 * hard-codes no mod name). It is the pure-DATA description of a reward pool a consumer rolls at its
 * end-game choke-point: a fixed {@link #guaranteed} list plus a weighted, score-gated {@link #pool} whose
 * size scales with the player's run score - the asset-driven seam so loot is tuned purely as DATA, no code
 * change. A consumer's preset references a table by id (e.g. {@code InstancePresetAsset.RewardTableId}).
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors common's {@code MultiPhaseBossAsset} /
 * {@code InstancePresetAsset} field-for-field). The engine decodes it DIRECTLY into typed fields via
 * {@link #CODEC} - the codec IS the single schema authority on both the pack layer and the owner layer.
 * The schema is 100% generic: common ships ZERO jar defaults, a consumer authors every table as pack JSON.
 *
 * <p>The reward lists are {@code String[]}s of compact specs (the codec has no list-of-objects form, the
 * same reason {@code InstancePresetAsset.Rewards} is a {@code String[]}): {@link #guaranteed} parses via
 * {@link InstanceReward#parseAll} (fixed grants), {@link #pool} via {@link LootEntry#parseAll} (weighted /
 * score-gated / quantity-ranged). Every {@code KeyedCodec} field name is PascalCase (the constructor
 * rejects a lower-case first letter at static init; {@code AssetCodecInitTest} guards it).
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "chase_nightmare",
 *   "Guaranteed": [ "item Ingredient_Life_Essence 1", "item KweebecNightmare_Moonbloom 2" ],
 *   "Rolls": 2, "ScorePerBonusRoll": 1800, "MaxRolls": 5,
 *   "Pool": [ "w10 item KweebecNightmare_Gustbloom 2-3",
 *             "w6 s2000 item KweebecNightmare_Emberbloom 1-2",
 *             "w3 s4000 item Ingredient_Life_Essence_Concentrated 1" ] }
 * }</pre>
 */
public final class LootTableAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, LootTableAsset>> {

    /** Default base weighted picks when {@code Rolls} is absent. */
    private static final int DEFAULT_ROLLS = 1;
    /** Default roll ceiling when {@code MaxRolls} is absent (a safety cap so a stray bonus-roll setting can't run away). */
    private static final int DEFAULT_MAX_ROLLS = 8;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String[] guaranteed;
    @Nullable private String[] pool;
    private int rolls = DEFAULT_ROLLS;
    private int scorePerBonusRoll = 0;
    private int maxRolls = DEFAULT_MAX_ROLLS;

    public static final AssetBuilderCodec<String, LootTableAsset> CODEC = AssetBuilderCodec.builder(
                    LootTableAsset.class,
                    LootTableAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the authoritative key is the
            // filename) - a no-op setter so it doesn't trip "Unused key(s)".
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("Guaranteed", Codec.STRING_ARRAY, false), (a, v) -> a.guaranteed = v, a -> a.guaranteed)
            .add()
            .append(new KeyedCodec<>("Pool", Codec.STRING_ARRAY, false), (a, v) -> a.pool = v, a -> a.pool)
            .add()
            .append(new KeyedCodec<>("Rolls", Codec.INTEGER, false), (a, v) -> a.rolls = v, a -> a.rolls)
            .add()
            .append(new KeyedCodec<>("ScorePerBonusRoll", Codec.INTEGER, false), (a, v) -> a.scorePerBonusRoll = v, a -> a.scorePerBonusRoll)
            .add()
            .append(new KeyedCodec<>("MaxRolls", Codec.INTEGER, false), (a, v) -> a.maxRolls = v, a -> a.maxRolls)
            .add()
            .build();

    public LootTableAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the runtime {@link LootTable}: the guaranteed specs parsed as fixed {@link InstanceReward}s and
     * the pool specs parsed as {@link LootEntry}s, with the authored / default roll knobs. Malformed specs
     * are skipped (never throw); an absent list yields an empty list. The id is the map key (the table
     * carries no id of its own), so this needs no id arg.
     */
    @Nonnull
    public LootTable toLootTable() {
        List<InstanceReward> g = InstanceReward.parseAll(guaranteed);
        List<LootEntry> p = LootEntry.parseAll(pool);
        return new LootTable(new ArrayList<>(g), p, rolls, scorePerBonusRoll, maxRolls);
    }
}
