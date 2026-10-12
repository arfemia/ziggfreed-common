package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * One tab on the {@link LeaderboardPage}: a bucket key (the {@link Leaderboard} partition
 * to show) + a client-resolved tab label. The consumer supplies the ordered tab list
 * (Kweebec: Solo / Duo / Trio / Squad over the party-size buckets "1".."4").
 *
 * <p>The page draws every tab from zc's kit (a view tab or a segment), whose label is a rich button's
 * {@code .TextSpans}, so any label, one that carries parameters (a season run's: the event's name and its
 * year) included, substitutes them and reads in each player's own language. {@link #spans} marks such a label
 * as before; the page paints both kinds alike.
 *
 * @param spans whether the label carries parameters or joins parts (painted on a {@code .TextSpans} either way)
 */
public record LeaderboardBucketTab(@Nonnull String bucketKey, @Nonnull Message label, boolean spans) {

    /** A tab whose label is a bare translation or plain text. */
    public LeaderboardBucketTab(@Nonnull String bucketKey, @Nonnull Message label) {
        this(bucketKey, label, false);
    }

    /** A tab whose label carries parameters. */
    @Nonnull
    public static LeaderboardBucketTab withSpans(@Nonnull String bucketKey, @Nonnull Message label) {
        return new LeaderboardBucketTab(bucketKey, label, true);
    }
}
