package com.ziggfreed.common.track.gain;

import javax.annotation.Nonnull;

import com.ziggfreed.common.track.world.TrackRules;
import com.ziggfreed.common.track.world.TrackWorldRules;

/**
 * The {@value GainFactors#WORLD} factor: the world's track rules ({@link TrackWorldRules}) for the track, as a fixed
 * multiplier the plan keeps until the player changes world or the rules load again: 0 where the world turns the
 * track off, the most specific {@code Multiplier} written for it otherwise, nothing at all in a world no rules
 * name.
 */
final class WorldGainFactor implements GainFactor {

    public static final WorldGainFactor INSTANCE = new WorldGainFactor();

    private WorldGainFactor() {
    }

    @Nonnull
    @Override
    public GainStep compile(@Nonnull GainScope scope) {
        TrackRules rules = TrackWorldRules.forWorld(scope.world());
        return rules == null ? GainStep.NONE : GainStep.times(rules.gainMultiplier(scope.trackId(), scope.categoryId()));
    }
}
