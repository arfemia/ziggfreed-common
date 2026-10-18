package com.ziggfreed.common.track;

import javax.annotation.Nonnull;

/**
 * What a gain becomes before it lands on a climb: the one seam in front of {@link TrackService#add}, where the gain
 * pipeline sits ({@code gain.GainPipeline}: the track's named factors run in its order, a world's rule, the boost,
 * the rested pool). The library installs the pipeline; a consumer replaces the whole seam only through
 * {@link TrackService#gain(TrackGain)}, and adds a factor through {@code gain.GainFactors} instead.
 *
 * <p>Called on the subject's own thread, once per {@link TrackService#add}, after the track is known and the
 * player meets its {@code Requires}, and before the cap and the fraction carried from the last gain apply. Answer
 * 0 to land nothing; a gain may answer more or less than it was asked, or a different sign. A gain that spends
 * something the record keeps (the rested pool) writes the spent record back through {@link GainCall#record}.
 */
@FunctionalInterface
public interface TrackGain {

    /** Every gain lands as asked. */
    TrackGain AS_ASKED = GainCall::asked;

    /** What {@code call}'s asked amount lands as. */
    double apply(@Nonnull GainCall call);
}
