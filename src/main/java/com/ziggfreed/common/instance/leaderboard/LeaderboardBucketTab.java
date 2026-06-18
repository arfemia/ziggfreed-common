package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * One tab on the {@link LeaderboardPage}: a bucket key (the {@link Leaderboard} partition
 * to show) + a client-resolved tab label. The consumer supplies the ordered tab list
 * (Kweebec: Solo / Duo / Trio / Squad over the party-size buckets "1".."4").
 */
public record LeaderboardBucketTab(@Nonnull String bucketKey, @Nonnull Message label) {
}
