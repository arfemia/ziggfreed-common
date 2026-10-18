package com.ziggfreed.common.track;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.track.curve.CurveTable;

/**
 * One climb read against its track: the record as kept, and what it reads as on the track's curve.
 *
 * @param key    whose climb on which track
 * @param def    the track and its curve
 * @param record the climb as kept
 */
public record TrackReading(@Nonnull TrackKey key, @Nonnull TrackDef def, @Nonnull TrackRecord record) {

    /** The current climb's level. */
    public int level() {
        return def.level(record);
    }

    /** The best level ever reached: what gates read. */
    public int best() {
        return def.best(record);
    }

    /** How many times this climb was started again. */
    public int prestige() {
        return record.prestige();
    }

    /** The current climb's value. */
    public long value() {
        return record.value();
    }

    /** The tier the best level stands in, or null. */
    @Nullable
    public CurveTable.Tier tier() {
        return def.tier(record);
    }
}
