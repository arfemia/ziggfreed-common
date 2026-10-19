package com.ziggfreed.common.track.gain;

import javax.annotation.Nonnull;

import com.ziggfreed.common.track.GainCall;

/**
 * One factor compiled for one player's track: what it does to a gain on the hot path, with nothing left to look
 * up. A factor whose answer is fixed until the plan's epoch moves compiles to {@link #times}; one that reads the
 * gain itself (the rested pool spends what the record keeps) is a step of its own.
 */
@FunctionalInterface
public interface GainStep {

    /** Changes nothing: what a factor with nothing to say for this track compiles to. */
    GainStep NONE = (call, amount) -> amount;

    /**
     * What {@code amount} (already through the steps before this one) becomes. On the subject's own thread; a step
     * that spends something the record keeps writes the spent record back through {@link GainCall#record}.
     */
    double apply(@Nonnull GainCall call, double amount);

    /**
     * Does this step say what it did itself ({@link GainCall#explain}), so the pipeline says nothing for it? Only
     * a step standing for several factors at once does (the factors a track adds together, {@code AdditiveStep}).
     */
    default boolean explainsItself() {
        return false;
    }

    /**
     * A fixed multiplier: 1 is {@link #NONE}, 0 (or anything below it, or not a number) stops the gain, so the
     * steps after it never run.
     */
    @Nonnull
    static GainStep times(double multiplier) {
        if (multiplier == 1.0) {
            return NONE;
        }
        double m = Double.isFinite(multiplier) && multiplier > 0.0 ? multiplier : 0.0;
        return (call, amount) -> amount * m;
    }
}
