package com.ziggfreed.common.track.gain;

import javax.annotation.Nonnull;

import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * The {@value GainFactors#PRESTIGE} factor: a prestiged climb's constant extra (R642, R852), the track's
 * {@code Prestige.GainPercent} (none unless written) on every gain once the climb has prestiged at least once, the
 * same whatever the count and however far the climb has come back. A track that never prestiges, writes a percent of
 * 0, or counts its extra inside the boosts' cap ({@code Prestige.InBoostCap}, which the {@code Boost} factor pays)
 * compiles to {@link GainStep#NONE}; the prestige count is read off the gain's own record, so a prestige needs no
 * plan built again.
 */
final class PrestigeGainFactor implements GainFactor {

    public static final PrestigeGainFactor INSTANCE = new PrestigeGainFactor();

    private PrestigeGainFactor() {
    }

    @Nonnull
    @Override
    public GainStep compile(@Nonnull GainScope scope) {
        TrackAsset track = scope.def().asset();
        double percent = track.prestigeGainPercent();
        if (!track.prestiges() || percent == 0.0 || track.prestigeInBoostCap()) {
            return GainStep.NONE;
        }
        double multiplier = Math.max(0.0, 1.0 + percent / 100.0);
        return (call, amount) -> call.before().prestige() > 0 ? amount * multiplier : amount;
    }
}
