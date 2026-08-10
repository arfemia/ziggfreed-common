package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link WeightedPrefabPlacementAsset}s,
 * keyed by lowercase placement id. A consumer's arena / level-design builder reads the
 * folded table back through this singleton and seed-selects a per-round subset from it,
 * so a pack adds, removes, or relocates the placement candidates purely as DATA (drop a
 * file under {@code Server/ZiggfreedCommon/PrefabPlacements/*.json}), never a code edit.
 *
 * <p>The fold mechanics (the three layers, lower-casing, idempotent re-import, resolve
 * order) live in the shared {@link AbstractKeyedAssetConfig} base; this singleton holds
 * the {@link WeightedPrefabPlacementAsset} itself as the resolved type (it has no
 * separate runtime model), adds {@link #getInstance()}, and ports Kweebec's
 * {@code StructureCatalog} selection helper as a PURE, deterministic
 * {@link #select(long, int, Predicate)} / {@link #selectWeighted(long, int, Predicate)}
 * - the caller supplies the seed and the keep-clear filter, so the helper carries no
 * engine dependency and reproduces the same chosen set + order for the same seed.
 *
 * <p>The store ships ZERO baked-in defaults (all content is consumer pack JSON, per the
 * library paradigm); a consumer with a Java baseline floor calls {@link #loadDefaults}
 * once at setup. The store is registered by the consumer at
 * {@code Server/ZiggfreedCommon/PrefabPlacements} via {@code AssetStoreRegistrar}.
 */
public final class WeightedPrefabPlacementConfig
        extends AbstractKeyedAssetConfig<WeightedPrefabPlacementAsset> {

    private static final WeightedPrefabPlacementConfig INSTANCE = new WeightedPrefabPlacementConfig();

    @Nonnull
    public static WeightedPrefabPlacementConfig getInstance() {
        return INSTANCE;
    }

    private WeightedPrefabPlacementConfig() {
    }

    /**
     * All effective placements (defaults overlaid by pack overlaid by owner) that carry a
     * non-blank {@code PrefabKey} - a placement with no prefab key can never paste, so it
     * is skipped best-effort. A fresh snapshot; safe to iterate.
     */
    @Nonnull
    public List<WeightedPrefabPlacementAsset> placements() {
        List<WeightedPrefabPlacementAsset> out = new ArrayList<>();
        for (WeightedPrefabPlacementAsset a : all().values()) {
            String key = a.prefabKey();
            if (key != null && !key.isBlank()) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * Deterministically select a per-round subset of placements: read the effective
     * (folded) candidate table, UNIFORM-shuffle it by {@code seed}, drop any the caller's
     * {@code accept} predicate rejects (e.g. a keep-clear footprint mask), and keep the
     * first {@code max}. The same seed + filter (and the same loaded packs) always yields
     * the same set + order; different seeds shuffle to a different chosen subset. Pure:
     * no engine touch, no {@link Math#random()} (the {@link Random} is seeded by the
     * caller-supplied {@code seed}).
     *
     * <p>Returns at most {@code max} and as few as 0 (if the filter rejects everything, or
     * a replace-pack authored no clear placements - the caller treats the list
     * best-effort). The {@code weight} field is ignored here; use
     * {@link #selectWeighted(long, int, Predicate)} for a weight-biased pick.
     *
     * @param seed   per-round seed (e.g. the world seed, or {@code roundId.hashCode()})
     * @param max    the maximum number of placements to keep
     * @param accept a keep predicate (placements the caller rejects are skipped), or a
     *               predicate that always returns {@code true} to keep all candidates
     * @return the chosen placements, in the seed's shuffled order
     */
    @Nonnull
    public List<WeightedPrefabPlacementAsset> select(long seed, int max,
                                                     @Nonnull Predicate<WeightedPrefabPlacementAsset> accept) {
        Random rng = new Random(seed);
        List<WeightedPrefabPlacementAsset> shuffled = new ArrayList<>(placements());
        java.util.Collections.shuffle(shuffled, rng);

        List<WeightedPrefabPlacementAsset> chosen = new ArrayList<>(Math.max(0, max));
        for (WeightedPrefabPlacementAsset p : shuffled) {
            if (chosen.size() >= max) {
                break;
            }
            if (accept.test(p)) {
                chosen.add(p);
            }
        }
        return chosen;
    }

    /**
     * Deterministically select up to {@code max} placements with a WEIGHT-biased draw:
     * each candidate's {@link WeightedPrefabPlacementAsset#weight()} biases its draw order
     * (a higher weight is more likely to be drawn earlier), the caller's {@code accept}
     * predicate filters as in {@link #select}, and the draw is without replacement. Pure +
     * deterministic for the same {@code seed} + filter + loaded packs. A non-positive
     * weight is treated as a tiny epsilon so the entry can still be drawn last.
     *
     * @param seed   per-round seed
     * @param max    the maximum number of placements to keep
     * @param accept a keep predicate (rejected placements are skipped)
     * @return the chosen placements, in weighted-draw order
     */
    @Nonnull
    public List<WeightedPrefabPlacementAsset> selectWeighted(long seed, int max,
                                                             @Nonnull Predicate<WeightedPrefabPlacementAsset> accept) {
        Random rng = new Random(seed);
        // Weighted shuffle via the Efraimidis-Spirakis key (w_i drawn as pow(rng, 1/weight),
        // sorted descending) - a deterministic weighted ordering without replacement.
        List<WeightedPrefabPlacementAsset> pool = new ArrayList<>(placements());
        List<Keyed> keyed = new ArrayList<>(pool.size());
        for (WeightedPrefabPlacementAsset p : pool) {
            double w = p.weight();
            if (w <= 0.0) {
                w = 1.0e-6;
            }
            double key = Math.pow(rng.nextDouble(), 1.0 / w);
            keyed.add(new Keyed(p, key));
        }
        keyed.sort((a, b) -> Double.compare(b.key, a.key));

        List<WeightedPrefabPlacementAsset> chosen = new ArrayList<>(Math.max(0, max));
        for (Keyed k : keyed) {
            if (chosen.size() >= max) {
                break;
            }
            if (accept.test(k.placement)) {
                chosen.add(k.placement);
            }
        }
        return chosen;
    }

    /** A placement paired with its computed weighted-draw key (internal to {@link #selectWeighted}). */
    private static final class Keyed {
        private final WeightedPrefabPlacementAsset placement;
        private final double key;

        private Keyed(@Nonnull WeightedPrefabPlacementAsset placement, double key) {
            this.placement = placement;
            this.key = key;
        }
    }
}
