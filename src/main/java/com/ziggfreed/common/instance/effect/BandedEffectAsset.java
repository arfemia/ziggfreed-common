package com.ziggfreed.common.instance.effect;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * A pack-authorable, mod-agnostic banded effect: the asset-driven {@code (band, tier)
 * -> EntityEffect} mapping a consumer's escalation/proximity logic reads, loaded from a
 * consumer's {@code Server/<Mod>/BandedEffects/*.json} (the path is supplied at register
 * time, so common hard-codes no mod name). It is the codec/asset FACE of the existing
 * {@link EffectBand} / {@link EffectBandLadder} primitives - a pack authors the band
 * cutoffs + effect ids that {@link BandedEffectConfig#toLadder()} folds into a runtime
 * {@link EffectBandLadder}, so the band-to-effect mapping lives entirely in data, never
 * in the consumer's Java.
 *
 * <p>Two flavors of banded effect, exactly mirroring the {@link EffectBand} model:
 * <ul>
 *   <li>A <b>persistent band</b> ({@code Band} 1+, {@code OneShot} false): a held
 *       {@code EffectId} a consumer keeps applied while a tracked value (proximity,
 *       escalation tier, ...) maps to that band, swapped with hysteresis. A higher
 *       {@code Band} = a deeper / more intense rung.</li>
 *   <li>A <b>one-shot band</b> ({@code Band} 0, {@code OneShot} true): a fire-once
 *       {@code EffectId} hard apply + optional {@code SoundId} stinger + optional
 *       {@code ShakeId}/{@code ShakeIntensity} camera shake a consumer fires on a moment
 *       (a hit, a near-miss, a jumpscare), never held.</li>
 * </ul>
 * {@code MinTier} gates a band to an escalation floor: a consumer never applies a band
 * whose {@code MinTier} exceeds the encounter's current tier (a deeper band can be
 * reserved for a late-encounter tier). The horror/"scare" naming of Kweebec's source
 * type is deliberately kept OUT of common - this is the neutral, reusable primitive.
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors {@link com.ziggfreed.common.instance.preset.InstancePresetAsset}
 * / hyMMO's {@code QuestGiverAsset} field-for-field). The engine decodes a banded effect
 * DIRECTLY into typed fields via {@link #CODEC} - the codec IS the single schema authority
 * on both the pack layer and any owner override (the same CODEC decodes an owner override).
 *
 * <p>Every {@code KeyedCodec} field name is PascalCase (the constructor rejects a
 * lower-case first letter at static init, throwing at server start).
 *
 * <p>Pack JSON shape (persistent band, then one-shot):
 * <pre>{@code
 * { "Name": "dread_2", "Band": 2, "MinTier": 0,
 *   "EffectId": "MyMod_Dread_2", "SoundId": "", "OneShot": false }
 *
 * { "Name": "stinger", "Band": 0, "MinTier": 0,
 *   "EffectId": "MyMod_Stinger", "SoundId": "SFX_MyMod_Stinger",
 *   "ShakeId": "MyMod_StingerShake", "ShakeIntensity": 0.7,
 *   "OneShot": true }
 * }</pre>
 */
public final class BandedEffectAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, BandedEffectAsset>> {

    /** {@code Band} value of a one-shot (non-banded) effect. */
    public static final int ONE_SHOT_BAND = 0;

    private String id;
    private AssetExtraInfo.Data data;

    private int band = 0;
    private int minTier = 0;
    @Nullable private String effectId;
    @Nullable private String soundId;
    @Nullable private String shakeId;
    private float shakeIntensity = 1.0f;
    private boolean oneShot = false;

    public static final AssetBuilderCodec<String, BandedEffectAsset> CODEC = AssetBuilderCodec.builder(
                    BandedEffectAsset.class,
                    BandedEffectAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the
            // authoritative key is the filename) - a no-op setter so it does not trip
            // the "Unused key(s)" warning, and is emitted on encode.
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id already comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("Band", Codec.INTEGER, false), (a, v) -> a.band = v, a -> a.band)
            .add()
            .append(new KeyedCodec<>("MinTier", Codec.INTEGER, false), (a, v) -> a.minTier = v, a -> a.minTier)
            .add()
            .append(new KeyedCodec<>("EffectId", Codec.STRING, false), (a, v) -> a.effectId = v, a -> a.effectId)
            .add()
            .append(new KeyedCodec<>("SoundId", Codec.STRING, false), (a, v) -> a.soundId = v, a -> a.soundId)
            .add()
            .append(new KeyedCodec<>("ShakeId", Codec.STRING, false), (a, v) -> a.shakeId = v, a -> a.shakeId)
            .add()
            .append(new KeyedCodec<>("ShakeIntensity", Codec.FLOAT, false), (a, v) -> a.shakeIntensity = v, a -> a.shakeIntensity)
            .add()
            .append(new KeyedCodec<>("OneShot", Codec.BOOLEAN, false), (a, v) -> a.oneShot = v, a -> a.oneShot)
            .add()
            .build();

    public BandedEffectAsset() {
    }

    /**
     * Build a banded effect in code (a consumer's Java baseline floor), without going
     * through the JSON {@link #CODEC}. The shipped {@code *.json} entries author the same
     * fields; a consumer's defaults source is the zero-pack source of truth.
     */
    @Nonnull
    public static BandedEffectAsset of(@Nonnull String id, int band, int minTier,
                                       @Nullable String effectId, @Nullable String soundId, boolean oneShot) {
        return of(id, band, minTier, effectId, soundId, null, 1.0f, oneShot);
    }

    /**
     * Full in-code factory including the one-shot camera-shake fields ({@code shakeId} +
     * {@code shakeIntensity}). The 6-arg {@link #of(String, int, int, String, String, boolean)}
     * delegates here with no shake; the JSON {@code CODEC} authors the same fields.
     */
    @Nonnull
    public static BandedEffectAsset of(@Nonnull String id, int band, int minTier,
                                       @Nullable String effectId, @Nullable String soundId,
                                       @Nullable String shakeId, float shakeIntensity, boolean oneShot) {
        BandedEffectAsset a = new BandedEffectAsset();
        a.id = id;
        a.band = band;
        a.minTier = minTier;
        a.effectId = effectId;
        a.soundId = soundId;
        a.shakeId = shakeId;
        a.shakeIntensity = shakeIntensity;
        a.oneShot = oneShot;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Band index this entry covers (1+ for a persistent band, {@link #ONE_SHOT_BAND} (0)
     * for the non-banded one-shot). Maps to {@link EffectBand#bandIndex()}.
     */
    public int band() {
        return band;
    }

    /** Escalation-tier floor: a consumer skips this band when the encounter tier is below it. */
    public int minTier() {
        return minTier;
    }

    /**
     * The {@code EntityEffect} asset id a consumer applies at this band (e.g.
     * {@code MyMod_Dread_2}); blank/null means no effect for this band (the baseline /
     * clear rung). Maps to {@link EffectBand#effectId()}.
     */
    @Nullable
    public String effectId() {
        return effectId;
    }

    /**
     * An optional one-shot {@code SoundEvent} stinger id a consumer may play alongside
     * the effect; blank/null means no sound. (Advisory metadata; the {@link EffectBandLadder}
     * does not act on it.)
     */
    @Nullable
    public String soundId() {
        return soundId;
    }

    /**
     * An optional {@code CameraEffect} (camera-shake) asset id a consumer may fire alongside
     * a ONE-SHOT beat (a hit, a near-miss, a jumpscare); blank/null means no shake. Advisory
     * metadata - the {@link EffectBandLadder} does not act on it; a consumer hands it to its
     * own shake sender (e.g. {@code CameraShakeService.shake}). Held bands ignore it.
     */
    @Nullable
    public String shakeId() {
        return shakeId;
    }

    /** Camera-shake intensity in the engine's 0..1 space for {@link #shakeId()} (default {@code 1.0}). */
    public float shakeIntensity() {
        return shakeIntensity;
    }

    /**
     * {@code true} for a fire-once band (one-shot hard apply), {@code false} for a held
     * band a consumer swaps with hysteresis. Maps to {@link EffectBand#oneShot()}.
     */
    public boolean oneShot() {
        return oneShot;
    }
}
