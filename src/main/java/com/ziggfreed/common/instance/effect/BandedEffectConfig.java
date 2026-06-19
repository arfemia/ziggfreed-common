package com.ziggfreed.common.instance.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link BandedEffectAsset}s, keyed
 * by lowercase id - the asset-driven source of an {@link EffectBandLadder} (the existing
 * banded-effect primitive). A consumer authors its bands as pack JSON under
 * {@code Server/<Mod>/BandedEffects/*.json} and reads them back here, then folds them into
 * a runtime ladder via {@link #toLadder()}, so the band-to-effect mapping is DATA, never
 * hardcoded in the consumer.
 *
 * <p>The fold mechanics (the three layers, lower-casing, idempotent re-import, resolve
 * order) live in the shared {@link AbstractKeyedAssetConfig} base; this singleton holds
 * the {@link BandedEffectAsset} type itself as the resolved value (there is no per-asset
 * model mapper - the runtime model is the collection-level {@link EffectBandLadder}) and
 * adds the type-specific query + ladder helpers.
 *
 * <p>The horror/"scare" naming of the Kweebec source type is kept OUT of common; this is
 * the neutral, reusable banded-effect store any minigame / dungeon / raid consumes.
 */
public final class BandedEffectConfig extends AbstractKeyedAssetConfig<BandedEffectAsset> {

    private static final BandedEffectConfig INSTANCE = new BandedEffectConfig();

    @Nonnull
    public static BandedEffectConfig getInstance() {
        return INSTANCE;
    }

    private BandedEffectConfig() {
    }

    /**
     * A banded effect by id, or {@code null} if unknown (no default fallback). Thin
     * convenience over {@link #resolve(String)} that null-guards a blank id.
     */
    @Nullable
    public BandedEffectAsset byId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return resolve(id);
    }

    /**
     * Build an {@link EffectBandLadder} from the authored PERSISTENT bands (a held swap
     * ladder), ordered by ascending {@link BandedEffectAsset#band()}. One-shot entries are
     * excluded (they are not a held band a consumer snaps a continuous value to). The
     * ladder's parallel numeric scale is the band index itself, so
     * {@link EffectBandLadder#nearestBandIndex(double)} snaps a live band value onto the
     * authored rungs.
     *
     * <p>Built via {@link EffectBandLadder#ofParallel(double[], String[])}: the {@code i}-th
     * rung pairs the {@code i}-th authored band's index with its effect id (null/blank =
     * baseline / no-effect rung).
     */
    @Nonnull
    public EffectBandLadder toLadder() {
        List<BandedEffectAsset> persistent = new ArrayList<>();
        for (BandedEffectAsset a : all().values()) {
            if (a != null && !a.oneShot()) {
                persistent.add(a);
            }
        }
        persistent.sort(java.util.Comparator.comparingInt(BandedEffectAsset::band));
        double[] bands = new double[persistent.size()];
        String[] effectIds = new String[persistent.size()];
        for (int i = 0; i < persistent.size(); i++) {
            BandedEffectAsset a = persistent.get(i);
            bands[i] = a.band();
            effectIds[i] = a.effectId();
        }
        return EffectBandLadder.ofParallel(bands, effectIds);
    }

    /**
     * The persistent (held) banded effect for a computed {@code band}, gated by the
     * encounter's current {@code tier}. Returns the highest-{@link BandedEffectAsset#band()}
     * entry whose band is at or below the computed band and whose
     * {@link BandedEffectAsset#minTier() minTier} is at or below the tier, or {@code null}
     * if none qualifies (band 0 / no match = clear).
     * Never returns a {@link BandedEffectAsset#oneShot()} entry.
     *
     * <p>Choosing the highest qualifying band (rather than an exact match) means a sparse
     * authored table still produces a sensible result - if only band 1 and band 3 are
     * authored, a computed band-2 resolves to band 1. (Mirrors the Kweebec source's
     * band selection, generalized to a neutral tier.)
     */
    @Nullable
    public BandedEffectAsset bandFor(int band, int tier) {
        if (band <= 0) {
            return null;
        }
        BandedEffectAsset best = null;
        for (BandedEffectAsset a : all().values()) {
            if (a == null || a.oneShot() || a.band() <= 0) {
                continue;
            }
            if (a.band() > band || a.minTier() > tier) {
                continue;
            }
            if (best == null || a.band() > best.band()) {
                best = a;
            }
        }
        return best;
    }

    /**
     * The first one-shot banded effect (the first {@link BandedEffectAsset#oneShot()}
     * entry), or {@code null} if none is authored. A consumer fires this on a moment
     * (a hit, a near-miss), never held.
     */
    @Nullable
    public BandedEffectAsset oneShot() {
        for (BandedEffectAsset a : all().values()) {
            if (a != null && a.oneShot()) {
                return a;
            }
        }
        return null;
    }

    /** The fully-folded {@code id -> banded effect} view (defaults, then pack, then owner). */
    @Nonnull
    public Map<String, BandedEffectAsset> bandedEffects() {
        return all();
    }
}
