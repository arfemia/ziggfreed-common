package com.ziggfreed.common.scaling;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How a set of participant power scalars is folded into ONE effective power for
 * {@link ScalingEngine#resolve difficulty resolution}. Domain-free: it says nothing
 * about players, parties, or instances - only how to combine a {@code double[]}.
 *
 * <ul>
 *   <li>{@link #SOLO} - the first participant only (a single-player context).</li>
 *   <li>{@link #AVERAGE} - the arithmetic mean (the sane open-world default: a mixed group
 *       resolves to its middle).</li>
 *   <li>{@link #PEAK} - the maximum (raid-favoring: the strongest participant sets the bar).</li>
 *   <li>{@link #WEIGHTED} - a power-weighted mean (high powers drive the result harder than a
 *       plain mean; the instance default).</li>
 *   <li>{@link #DISABLED} - no aggregation; the engine returns the base difficulty untouched.</li>
 * </ul>
 */
public enum AggregationMode {
    SOLO,
    AVERAGE,
    PEAK,
    WEIGHTED,
    DISABLED;

    /**
     * Parse a mode from a config / API string, case-insensitively, returning {@code fallback}
     * for a {@code null}, blank, or unrecognised name (never throws). Lets a consumer accept a
     * free-string mode from JSON without its own parse plumbing.
     */
    @Nonnull
    public static AggregationMode fromName(@Nullable String name, @Nonnull AggregationMode fallback) {
        if (name == null) {
            return fallback;
        }
        String trimmed = name.trim();
        for (AggregationMode mode : values()) {
            if (mode.name().equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }
        return fallback;
    }
}
