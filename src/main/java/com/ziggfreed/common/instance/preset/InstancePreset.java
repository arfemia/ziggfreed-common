package com.ziggfreed.common.instance.preset;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.RewardOnExit;
import com.ziggfreed.common.lobby.LobbyConfig;

/**
 * The normalized runtime form of an {@link InstancePresetAsset}: the CROSS-CUTTING,
 * non-gameplay layer of one instance preset (queue policy, display keys, leaderboard
 * config, the reward list). It is keyed by the SAME lowercase preset id the consumer's
 * gameplay config uses (e.g. Kweebec's {@code RuleSet}), so the two compose by id
 * without a twin schema - gameplay knobs stay on the consumer's own asset, the
 * cross-cutting knobs live here.
 *
 * <p>{@link #toLobbyConfig(int, int)} folds the authored queue policy with the
 * consumer-supplied {@code minParty}/{@code maxParty} (which stay on the gameplay
 * config so the arena-budget clamp is not duplicated), producing the {@link LobbyConfig}
 * the matchmaking queue is built from.
 */
public record InstancePreset(@Nonnull String id, boolean enabled, @Nullable String nameKey,
                             @Nullable String descriptionKey, int fillTimeoutSeconds, int countdownSeconds,
                             boolean allowSolo, boolean leaderForceStart, @Nonnull LeaderboardBucket leaderboardBucket,
                             @Nullable String leaderboardKey, @Nonnull List<InstanceReward> rewards,
                             @Nonnull RewardOnExit rewardOnExit, @Nonnull QueueModeSet queueModes) {

    public InstancePreset {
        rewards = List.copyOf(rewards);
    }

    /** Build the queue policy, taking party min/max from the consumer's gameplay config. */
    @Nonnull
    public LobbyConfig toLobbyConfig(int minParty, int maxParty) {
        return new LobbyConfig(minParty, maxParty, fillTimeoutSeconds, countdownSeconds, allowSolo, leaderForceStart);
    }
}
