package com.ziggfreed.common.track;

import java.util.List;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.track.asset.DerivedReading;

/**
 * A derived track resolved against what loaded ({@link TrackService#derivation}): the track, how it reads its sources,
 * and its usable sources in order (the ones its {@code From.Tracks} names, then its categories' tracks by
 * {@code Order} then id, each once; a source switched off, curve-less, itself derived or the track itself left out).
 * Never empty: a derived track with no usable source has no derivation and is not usable.
 *
 * <p>The rule is one place for every reader: {@link #combine} (the highest, or the sum) and {@link #highest} (the
 * first source at the highest level). A reader that keeps its sources' levels elsewhere (a consumer's own record of
 * the same climbs) reads them through it, so the two never disagree.
 *
 * @param def     the derived track and its curve
 * @param reading how it reads its sources
 * @param sources its usable sources, in order
 */
public record TrackDerivation(@Nonnull TrackDef def, @Nonnull DerivedReading reading, @Nonnull List<TrackDef> sources) {

    public TrackDerivation {
        sources = List.copyOf(sources);
    }

    /** The derived track's level when {@code levelOf} gives each source's: the highest, or the sum. */
    public int level(@Nonnull ToIntFunction<TrackDef> levelOf) {
        return combine(reading, sources, levelOf);
    }

    /**
     * The source a write on this track lands on when {@code levelOf} gives each source's level: under Highest the
     * first source at the highest level; under Sum none (null), since no one source holds the sum.
     */
    @Nullable
    public TrackDef writeTarget(@Nonnull ToIntFunction<TrackDef> levelOf) {
        return reading == DerivedReading.HIGHEST ? highest(sources, levelOf) : null;
    }

    /** Does this track read {@code trackId} (any case)? */
    public boolean readsFrom(@Nullable String trackId) {
        String wanted = TrackKey.normalize(trackId);
        for (TrackDef source : sources) {
            if (source.id().equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code items}' levels read as {@code reading} reads them: the highest, or the sum (held at
     * {@link Integer#MAX_VALUE}); 0 for none. Pure.
     */
    public static <T> int combine(@Nonnull DerivedReading reading, @Nonnull List<T> items,
            @Nonnull ToIntFunction<T> levelOf) {
        if (reading == DerivedReading.SUM) {
            long sum = 0L;
            for (T item : items) {
                sum += Math.max(0, levelOf.applyAsInt(item));
            }
            return (int) Math.min(Integer.MAX_VALUE, sum);
        }
        int highest = 0;
        for (T item : items) {
            highest = Math.max(highest, levelOf.applyAsInt(item));
        }
        return highest;
    }

    /** The first of {@code items} at the highest level {@code levelOf} gives, or null for none. Pure. */
    @Nullable
    public static <T> T highest(@Nonnull List<T> items, @Nonnull ToIntFunction<T> levelOf) {
        T best = null;
        int bestLevel = Integer.MIN_VALUE;
        for (T item : items) {
            int level = levelOf.applyAsInt(item);
            if (best == null || level > bestLevel) {
                best = item;
                bestLevel = level;
            }
        }
        return best;
    }
}
