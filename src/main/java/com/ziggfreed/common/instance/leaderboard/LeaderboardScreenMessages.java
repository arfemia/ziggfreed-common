package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The locale-free chrome text for the {@link LeaderboardPage}: the title, the column
 * headers, the empty state, and the "your rank" footer. The consumer returns pre-built
 * client-resolved {@link Message}s; common stays locale-free.
 */
public interface LeaderboardScreenMessages {

    @Nonnull Message title();

    // ---- Optional leading row labels (default null = no label shown). Clarify which axis each
    // selector row drives; the consumer overrides to name its axes (Kweebec: Difficulty / Players). ----

    /** Leading label for the PRIMARY tab axis (e.g. "Difficulty"); null hides it. */
    @Nullable default Message primaryAxisLabel() {
        return null;
    }

    /** Leading label for the SECONDARY tab axis (e.g. "Players"); null hides it. */
    @Nullable default Message secondaryAxisLabel() {
        return null;
    }

    /** Leading label for the sort-toggle row (e.g. "Sort"); null hides it. */
    @Nullable default Message sortLabel() {
        return null;
    }

    /** Leading label for the Rankings/Stats view toggle (e.g. "View"); null hides it. */
    @Nullable default Message viewLabel() {
        return null;
    }

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

    /**
     * Label for the synthesized "All" tab the page appends to the end of any axis with two or more
     * concrete tabs (e.g. "All"): selecting it aggregates every concrete bucket on that axis, so
     * All+All is the full lifetime roll-up. Required because the page renders this tab itself.
     */
    @Nonnull Message filterAll();

    /** Footer: the viewer's own rank + best score in the selected bucket. */
    @Nonnull Message yourRank(int rank, long bestScore);

    /** Footer when the viewer has no ranked entry in the selected bucket. */
    @Nonnull Message yourRankNone();
}
