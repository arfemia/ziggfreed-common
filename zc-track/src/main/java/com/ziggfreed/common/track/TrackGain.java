package com.ziggfreed.common.track;

import javax.annotation.Nonnull;

import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * What a gain becomes before it lands on a climb: the one seam in front of {@link TrackService#add}, where a
 * gain pipeline (named factors run in order, boosts, a world's rule) sits. The library's default lands every
 * gain as asked ({@link #AS_ASKED}); a pipeline installs itself through {@link TrackService#gain(TrackGain)}.
 *
 * <p>Called on the subject's own thread, once per {@link TrackService#add}, after the track is known and the
 * player meets its {@code Requires}, and before the cap and the fraction carried from the last gain apply.
 * Answer 0 to land nothing; a gain may answer more or less than it was asked, or a different sign.
 */
@FunctionalInterface
public interface TrackGain {

    /** Every gain lands as asked. */
    TrackGain AS_ASKED = (key, track, amount, source) -> amount;

    /**
     * What {@code amount}, asked for {@code key}'s climb on {@code track} by {@code source}, lands as.
     *
     * @param source what asked: a reward's source id, {@code admin}, a moment's own label
     */
    double apply(@Nonnull TrackKey key, @Nonnull TrackAsset track, double amount, @Nonnull String source);
}
