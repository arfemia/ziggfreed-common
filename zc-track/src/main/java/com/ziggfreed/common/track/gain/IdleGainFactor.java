package com.ziggfreed.common.track.gain;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.ziggfreed.common.entity.PlayerActivity;

/**
 * The {@value GainFactors#IDLE} factor: a gain pays nothing while its player has gone longer than the track settings'
 * {@code Idle.Minutes} without moving, looking around or using anything (zc-core's {@link PlayerActivity}), so an
 * idle player's farm, a mob grinder left running or a gain trickling in while away earns nothing. The threshold is
 * read when the plan is built (a settings fold builds every plan again); whether the player is idle is asked at each
 * gain. No threshold (0, or no settings record) and no player compile to {@link GainStep#NONE}.
 */
final class IdleGainFactor implements GainFactor {

    public static final IdleGainFactor INSTANCE = new IdleGainFactor();

    private IdleGainFactor() {
    }

    @Nonnull
    @Override
    public GainStep compile(@Nonnull GainScope scope) {
        long threshold = TrackSettingsAsset.Config.getInstance().settings().idleMillis();
        UUID player = scope.playerId();
        if (threshold <= 0L || player == null) {
            return GainStep.NONE;
        }
        return (call, amount) -> PlayerActivity.isIdle(player, threshold, System.currentTimeMillis()) ? 0.0 : amount;
    }
}
