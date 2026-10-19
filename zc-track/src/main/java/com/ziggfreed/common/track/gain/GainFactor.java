package com.ziggfreed.common.track.gain;

import javax.annotation.Nonnull;

/**
 * One named factor of the gain pipeline (a world's rule, a class multiplier, a boost, the rested pool, a level
 * cap), registered under its id ({@link GainFactors#register}) and run where a track's {@code Gain.Factors} names
 * it. It is compiled once per player and track into a {@link GainStep} and kept until the plan's epoch moves
 * ({@link GainPlans}), so all its lookups happen here, never per gain.
 */
@FunctionalInterface
public interface GainFactor {

    /**
     * Says nothing on any track: what a factor id holds until its owner registers it (the boost, until the boost
     * module's setup registers its own).
     */
    GainFactor NEUTRAL = scope -> GainStep.NONE;

    /**
     * This factor for {@code scope}'s player and track, as a step the hot path runs; {@link GainStep#NONE} when it
     * has nothing to say there. On the subject's own thread. A compile that throws counts as {@link GainStep#NONE},
     * warned once.
     */
    @Nonnull
    GainStep compile(@Nonnull GainScope scope);
}
