package com.ziggfreed.common.encounter.power;

import java.util.Map;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * How the library reads a player's power when no mod fills the player power seam: a base number plus a weighted
 * sum of factor readings (the {@link FactorFormula} arithmetic, so any reading any mod contributes may be an input),
 * held on the scale {@code Scale} names. The FILE NAME is the record's id, and the record the reading uses is
 * {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/PlayerPower/Default.json}, which this library ships reading the
 * player's worn gear (the mean item level of the armor slots and the best weapon on the hotbar); a pack's same-id
 * file wins by pack order, and the owner layer is {@code mods/ziggfreedcommon/player-power.json}:
 * <pre>{@code
 * { "Default": { "Inputs": { "Armor": { "Weight": 0.6 }, "Health": { "Weight": 0.05 } } } }
 * }</pre>
 * Each input is keyed by a name of its own, so an owner retunes one weight leaf by leaf and a pack adds an input by
 * adding a key; a key a file writes without a {@code Factor} inherits the one under it.
 */
public final class PlayerPowerAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, PlayerPowerAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/PlayerPower";

    /** The id of the record the reading uses (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    /** The scale's floor unless the record says otherwise; mirrors the shipped file. */
    public static final double DEFAULT_MIN = 1.0;

    /** The scale's ceiling unless the record says otherwise; mirrors the shipped file. */
    public static final double DEFAULT_MAX = 200.0;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Double base;
    @Nullable protected Scale scale;
    @Nullable protected Map<String, FactorFormula.Term> inputs;

    public static final AssetBuilderCodec<String, PlayerPowerAsset> CODEC = AssetBuilderCodec.builder(
                    PlayerPowerAsset.class,
                    PlayerPowerAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Base", Codec.DOUBLE, false),
                    (a, v) -> a.base = v, a -> a.base, (a, p) -> a.base = p.base)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("The power before any input is added. 0 when unauthored; the result is still held at "
                    + "Scale.Min, so a player with nothing reads the scale's floor.").add()
            .appendInherited(new KeyedCodec<>("Scale", Scale.CODEC, false),
                    (a, v) -> a.scale = v, a -> a.scale, (a, p) -> a.scale = p.scale)
            .documentation("The range a player's power lives on: the reading is held between Min and Max, and a mod "
                    + "that reads power (a mob scaler's difficulty caps) calibrates against them.").add()
            .appendInherited(new KeyedCodec<>("Inputs", new InheritMapCodec<>(
                                    FactorFormula.Term.codec(EditorDataSets.FACTORS)).documented(
                                    "The readings power adds up, each under a name of its own: an owner file or a "
                                            + "Parent retunes one by its name, and a pack adds one by adding a name.",
                                    "One input: Weight times what Factor reads for the player (with its own Param). "
                                            + "A factor nobody can answer adds 0."),
                            false),
                    (a, v) -> a.inputs = v, a -> a.inputs, (a, p) -> a.inputs = p.inputs)
            .documentation("What a player's power is made of. The library ships Armor "
                    + "(hytale:worn_item_level), Weapon (hytale:best_weapon_item_level) and Health "
                    + "(hytale:stat Health, weight 0).").add()
            .build();

    public PlayerPowerAsset() {
    }

    /** Java-side factory for a test: the same fields the codec fills. */
    @Nonnull
    public static PlayerPowerAsset of(@Nullable Double base, @Nullable Double min, @Nullable Double max,
            @Nullable Map<String, FactorFormula.Term> inputs) {
        PlayerPowerAsset asset = new PlayerPowerAsset();
        asset.id = SHARED_ID;
        asset.base = base;
        asset.scale = Scale.of(min, max);
        asset.inputs = inputs;
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The scale's floor; {@link #DEFAULT_MIN} when unauthored or not a number. */
    public double minPower() {
        Double min = scale == null ? null : scale.min;
        return min == null || !Double.isFinite(min) ? DEFAULT_MIN : min;
    }

    /** The scale's ceiling, never below the floor; {@link #DEFAULT_MAX} when unauthored or not a number. */
    public double maxPower() {
        Double max = scale == null ? null : scale.max;
        double ceiling = max == null || !Double.isFinite(max) ? DEFAULT_MAX : max;
        return Math.max(minPower(), ceiling);
    }

    /** The inputs as authored, keyed by name; empty when none are. */
    @Nonnull
    public Map<String, FactorFormula.Term> inputs() {
        return inputs == null ? Map.of() : inputs;
    }

    /**
     * The reading through {@code lookup} ({@code (factorId, param) -> value}): {@code Base} plus every input's
     * weighted reading ({@link FactorFormula#sum}, an unreadable input adding 0), held on the scale. Pure.
     */
    public double read(@Nonnull BiFunction<String, String, Double> lookup) {
        double base0 = base != null && Double.isFinite(base) ? base : 0.0;
        double sum = base0 + FactorFormula.sum(inputs().values().toArray(new FactorFormula.Term[0]), lookup);
        return Math.max(minPower(), Math.min(maxPower(), Double.isFinite(sum) ? sum : minPower()));
    }

    /** The Scale group: the range a player's power lives on. */
    public static final class Scale {

        @Nullable protected Double min;
        @Nullable protected Double max;

        public static final BuilderCodec<Scale> CODEC = BuilderCodec.builder(Scale.class, Scale::new)
                .appendInherited(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                        (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                .metadata(EditorSchema.defaultValue(DEFAULT_MIN))
                .documentation("The floor: what a player with nothing reads, and the least power anyone reads. "
                        + "1 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(DEFAULT_MAX))
                .documentation("The ceiling: no player reads more. 200 when unauthored.").add()
                .build();

        public Scale() {
        }

        @Nonnull
        static Scale of(@Nullable Double min, @Nullable Double max) {
            Scale scale = new Scale();
            scale.min = min;
            scale.max = max;
            return scale;
        }
    }
}
