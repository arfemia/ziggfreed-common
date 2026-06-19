package com.ziggfreed.common.instance.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An ordered ladder of {@link EffectBand}s parallel to a numeric scale, plus the
 * "snap a continuous value to the nearest rung" selector. Generalizes the per-entity
 * pace-band pattern (a {@code double[] speedBands} parallel to a {@code String[]
 * bandEffectIds}, picking the index nearest the live multiplier and swapping that
 * band's EntityEffect): here the ladder OWNS both the band cutoffs and the effect ids,
 * so a consumer asks {@link #nearestBandIndex(double)} for the rung and
 * {@link #effectIdFor(int)} for the id to apply.
 *
 * <p>Mod-agnostic and config-free: the consumer builds the ladder from its own
 * authored bands/ids (no hardcoded ids here). Immutable after construction; safe to
 * hold as a static / shared instance.
 *
 * <p>Pure logic, no engine calls: there is no world-thread requirement on this class.
 * The actual effect apply (which IS world-thread) lives in {@link EntityEffectService}.
 */
public final class EffectBandLadder {

    @Nonnull
    private final double[] bands;
    @Nonnull
    private final List<EffectBand> rungs;

    private EffectBandLadder(@Nonnull double[] bands, @Nonnull List<EffectBand> rungs) {
        this.bands = bands;
        this.rungs = Collections.unmodifiableList(rungs);
    }

    /**
     * Build a ladder from a {@code bands} scale parallel to {@code effectIds} (the
     * historical shape: index {@code i} pairs {@code bands[i]} with {@code effectIds[i]};
     * a null/blank id is the baseline / no-effect rung). The shorter of the two arrays
     * bounds the rung count, so a mismatched tail is ignored rather than throwing.
     *
     * @param bands     the numeric cutoffs (e.g. speed multipliers), in any order; the
     *                  selector picks the entry nearest a value
     * @param effectIds the effect id parallel to each band (null/blank = baseline)
     * @param oneShot   advisory {@link EffectBand#oneShot()} flag for every rung
     */
    @Nonnull
    public static EffectBandLadder ofParallel(@Nullable double[] bands, @Nullable String[] effectIds,
                                              boolean oneShot) {
        double[] b = bands == null ? new double[0] : bands.clone();
        String[] ids = effectIds == null ? new String[0] : effectIds;
        int n = Math.min(b.length, ids.length);
        List<EffectBand> rungs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rungs.add(new EffectBand(i, ids[i], oneShot));
        }
        // Keep the bands array at the rung count so the two stay aligned.
        double[] trimmed = new double[n];
        System.arraycopy(b, 0, trimmed, 0, n);
        return new EffectBandLadder(trimmed, rungs);
    }

    /** {@link #ofParallel(double[], String[], boolean)} with {@code oneShot=false}. */
    @Nonnull
    public static EffectBandLadder ofParallel(@Nullable double[] bands, @Nullable String[] effectIds) {
        return ofParallel(bands, effectIds, false);
    }

    /**
     * Build a ladder from an explicit rung list and its parallel numeric scale. The
     * shorter of the two bounds the rung count.
     */
    @Nonnull
    public static EffectBandLadder of(@Nullable double[] bands, @Nullable List<EffectBand> rungs) {
        double[] b = bands == null ? new double[0] : bands.clone();
        List<EffectBand> r = rungs == null ? List.of() : rungs;
        int n = Math.min(b.length, r.size());
        double[] trimmed = new double[n];
        System.arraycopy(b, 0, trimmed, 0, n);
        return new EffectBandLadder(trimmed, new ArrayList<>(r.subList(0, n)));
    }

    /** @return the number of rungs in this ladder. */
    public int size() {
        return rungs.size();
    }

    /** @return true when the ladder has no rungs (a no-op ladder). */
    public boolean isEmpty() {
        return rungs.isEmpty();
    }

    /** @return the immutable rung list. */
    @Nonnull
    public List<EffectBand> bands() {
        return rungs;
    }

    /**
     * Index of the band whose numeric cutoff is nearest {@code value} (ties pick the
     * earlier / lower-index band, matching the historical {@code nearestBandIndex}).
     * Returns {@code 0} for an empty ladder, which the caller can treat as "no band"
     * via {@link #isEmpty()}.
     *
     * @param value the live tracked value (e.g. a speed multiplier)
     * @return the nearest rung index, or {@code 0} if the ladder is empty
     */
    public int nearestBandIndex(double value) {
        return nearestBandIndex(bands, value);
    }

    /**
     * Static form of {@link #nearestBandIndex(double)} over a caller-supplied scale,
     * for a consumer that keeps its own {@code double[]} parallel to ids. Index of the
     * {@code scale} entry nearest {@code value}; ties pick the earlier / lower index.
     * Returns {@code 0} for a null/empty scale.
     */
    public static int nearestBandIndex(@Nullable double[] scale, double value) {
        if (scale == null || scale.length == 0) {
            return 0;
        }
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < scale.length; i++) {
            double diff = Math.abs(scale[i] - value);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /**
     * The effect id at {@code bandIndex}, or {@code null} for the baseline / no-effect
     * rung OR an out-of-range index (so a caller can safely pass the result of
     * {@link #nearestBandIndex(double)} on an empty ladder).
     */
    @Nullable
    public String effectIdFor(int bandIndex) {
        if (bandIndex < 0 || bandIndex >= rungs.size()) {
            return null;
        }
        EffectBand rung = rungs.get(bandIndex);
        return rung.hasEffect() ? rung.effectId() : null;
    }

    /** The {@link EffectBand} at {@code bandIndex}, or {@code null} if out of range. */
    @Nullable
    public EffectBand bandAt(int bandIndex) {
        if (bandIndex < 0 || bandIndex >= rungs.size()) {
            return null;
        }
        return rungs.get(bandIndex);
    }
}
