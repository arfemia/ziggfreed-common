package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * One tab on the {@link LeaderboardPage}: a bucket key (the {@link Leaderboard} partition
 * to show) + a client-resolved tab label. The consumer supplies the ordered tab list
 * (Kweebec: Solo / Duo / Trio / Squad over the party-size buckets "1".."4").
 *
 * <p>A tab is a {@code TextButton}, whose {@code .Text} resolves a bare translation but never substitutes
 * a parameter. A label that carries parameters (a season run's: the event's name and its year) is a
 * {@link #spans} tab instead, painted on a rich button's {@code .TextSpans}, so it substitutes them and
 * still reads in each player's own language.
 *
 * @param spans whether the label is painted on a rich button's {@code .TextSpans} rather than a {@code .Text}
 */
public record LeaderboardBucketTab(@Nonnull String bucketKey, @Nonnull Message label, boolean spans) {

    /** A tab whose label is a bare translation or plain text, painted on a {@code TextButton}. */
    public LeaderboardBucketTab(@Nonnull String bucketKey, @Nonnull Message label) {
        this(bucketKey, label, false);
    }

    /** A tab whose label carries parameters, painted on a rich button's {@code .TextSpans}. */
    @Nonnull
    public static LeaderboardBucketTab withSpans(@Nonnull String bucketKey, @Nonnull Message label) {
        return new LeaderboardBucketTab(bucketKey, label, true);
    }
}
