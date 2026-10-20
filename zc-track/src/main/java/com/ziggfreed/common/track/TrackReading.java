package com.ziggfreed.common.track;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.track.asset.DerivedReading;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.curve.CurveTable;

/**
 * One climb read against its track: the record as kept, and what it reads as on the track's curve. A derived track's
 * reading ({@code Derived}) also carries its sources' readings, and its level, best level and value read from them
 * ({@link TrackDerivation#combine}); its own record holds no value, only what is kept on the track itself.
 *
 * @param key     whose climb on which track
 * @param def     the track and its curve
 * @param record  the climb as kept (a derived track's own, which holds no value)
 * @param sources a derived track's sources, read for the same subject; empty for an ordinary track
 */
public record TrackReading(@Nonnull TrackKey key, @Nonnull TrackDef def, @Nonnull TrackRecord record,
                           @Nonnull List<TrackReading> sources) {

    public TrackReading {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** An ordinary track's reading: its record alone. */
    public TrackReading(@Nonnull TrackKey key, @Nonnull TrackDef def, @Nonnull TrackRecord record) {
        this(key, def, record, List.of());
    }

    /** Does this reading read other tracks (a derived track with its sources read)? */
    public boolean isDerived() {
        return !sources.isEmpty() && def.asset().isDerived();
    }

    /** The current climb's level; a derived track's, its sources' as it reads them. */
    public int level() {
        return isDerived() ? TrackDerivation.combine(readingRule(), sources, TrackReading::level) : def.level(record);
    }

    /** The best level ever reached: what gates read. A derived track's, its sources' best as it reads them. */
    public int best() {
        return isDerived() ? TrackDerivation.combine(readingRule(), sources, TrackReading::best) : def.best(record);
    }

    /** How many times this climb was started again (a derived track keeps none). */
    public int prestige() {
        return record.prestige();
    }

    /**
     * The current climb's value. A derived track's: the value of the source it reads under Highest ({@link #top}),
     * the sources' values added together under Sum.
     */
    public long value() {
        if (!isDerived()) {
            return record.value();
        }
        if (readingRule() == DerivedReading.HIGHEST) {
            TrackReading top = top();
            return top == null ? 0L : top.value();
        }
        long sum = 0L;
        for (TrackReading source : sources) {
            sum = CurveTable.saturatingAdd(sum, Math.max(0L, source.value()));
        }
        return sum;
    }

    /**
     * The source a Highest track reads and a write on it lands on: the first at the highest current level. Null for
     * an ordinary track and a Sum track.
     */
    @Nullable
    public TrackReading top() {
        return isDerived() && readingRule() == DerivedReading.HIGHEST
                ? TrackDerivation.highest(sources, TrackReading::level) : null;
    }

    /** The tier the best level stands in, or null. */
    @Nullable
    public CurveTable.Tier tier() {
        return isDerived() ? def.table().tierOf(best()) : def.tier(record);
    }

    @Nonnull
    private DerivedReading readingRule() {
        TrackAsset.Derived derived = def.asset().derived();
        return derived == null ? DerivedReading.HIGHEST : derived.reading();
    }
}
