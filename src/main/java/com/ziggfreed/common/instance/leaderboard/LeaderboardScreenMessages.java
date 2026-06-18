package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * The locale-free chrome text for the {@link LeaderboardPage}: the title, the column
 * headers, the empty state, and the "your rank" footer. The consumer returns pre-built
 * client-resolved {@link Message}s; common stays locale-free.
 */
public interface LeaderboardScreenMessages {

    @Nonnull Message title();

    @Nonnull Message colRank();

    @Nonnull Message colPlayer();

    @Nonnull Message colScore();

    @Nonnull Message colTime();

    @Nonnull Message colPlays();

    @Nonnull Message empty();

    /** Footer: the viewer's own rank + best score in the selected bucket. */
    @Nonnull Message yourRank(int rank, long bestScore);

    /** Footer when the viewer has no ranked entry in the selected bucket. */
    @Nonnull Message yourRankNone();
}
