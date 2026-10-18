package com.ziggfreed.common.track;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * What one gain's factors did, built only when asked for ({@link TrackService#explain}): what was asked, what the
 * factors landed it as, the whole points the climb actually moved by (after the fraction carried and the cap), and
 * each factor that ran, in order, as the multiplier it applied ({@code landed / asked} is their product). A factor
 * that stopped the gain (a world that pays nothing on the track) is the last one listed.
 *
 * @param key     whose climb, on which track
 * @param asked   what was asked
 * @param landed  what the factors made of it
 * @param granted how far the climb's value moved
 * @param factors each factor that ran, in order
 */
public record GainBreakdown(@Nonnull TrackKey key, double asked, double landed, long granted,
                            @Nonnull List<Factor> factors) {

    /** One factor's part: its id and the multiplier it applied (1 when it changed nothing). */
    public record Factor(@Nonnull String id, double multiplier) {
    }

    /** The multiplier factor {@code id} applied, or 1 when it did not run. */
    public double factor(@Nonnull String id) {
        for (Factor f : factors) {
            if (f.id().equalsIgnoreCase(id)) {
                return f.multiplier();
            }
        }
        return 1.0;
    }
}
