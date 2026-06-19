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

    /** The cumulative total-points column header (Rankings view). */
    @Nonnull Message colTotal();

    @Nonnull Message colTime();

    @Nonnull Message colPlays();

    @Nonnull Message empty();

    /** Sort-toggle button label: rank by best single-run score. */
    @Nonnull Message sortScore();

    /** Sort-toggle button label: rank by cumulative total points. */
    @Nonnull Message sortTotal();

    /** Sort-toggle button label: rank by fastest winning time. */
    @Nonnull Message sortTime();

    /** View-toggle button label: the score rankings. */
    @Nonnull Message viewRankings();

    /** View-toggle button label: the lifetime stats. */
    @Nonnull Message viewStats();

    /** Footer: the viewer's own rank + best score in the selected bucket. */
    @Nonnull Message yourRank(int rank, long bestScore);

    /** Footer when the viewer has no ranked entry in the selected bucket. */
    @Nonnull Message yourRankNone();
}
