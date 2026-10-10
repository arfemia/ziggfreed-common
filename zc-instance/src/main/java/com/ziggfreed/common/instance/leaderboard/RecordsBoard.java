package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nullable;

/**
 * A board the Records tab opens in place of the encounter board while it shows: the seam
 * {@link RecordsDestinations#setBoard} fills. With none filled, Records is the encounter board alone,
 * as it always was.
 */
public interface RecordsBoard {

    /** Whether the board has anything to show now. */
    boolean shows();

    /** The page to open now, or null when there is nothing to open, which leaves Records to the encounter board. */
    @Nullable
    LeaderboardPageDeps deps();
}
