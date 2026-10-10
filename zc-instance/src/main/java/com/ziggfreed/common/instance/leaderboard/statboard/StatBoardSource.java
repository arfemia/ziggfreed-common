package com.ziggfreed.common.instance.leaderboard.statboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.leaderboard.StatColumnDef;

/**
 * What the stat board asks of the store that keeps the stats it ranks: whether it is on, and what each
 * section line's column is. This module cannot see that store (it lives above, over the progression
 * moments), so the store fills this seam at setup through {@link StatBoard#setSource}.
 *
 * <p>The default, {@link #NONE}, knows no stat: every section is left without a column, so the board
 * shows no tab and Records opens the encounter board as it always has. That is the right answer on a
 * server where nothing counts stats, and it never shows a column nobody counts.
 */
public interface StatBoardSource {

    /** Nothing counts stats here: the board is off and knows no column. */
    StatBoardSource NONE = new StatBoardSource() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        @Nullable
        public StatColumnDef column(@Nonnull String line) {
            return null;
        }
    };

    /** Whether the stat board runs at all: false hides its tab, and its writes are the store's to skip. */
    boolean enabled();

    /**
     * The column for one section line (a stat id, or {@code Stat/Thing}), or null when nothing on this
     * server counts it, which leaves it out. Its {@link StatColumnDef#statKey()} is the line as the board
     * stores it; its label is a bare translation, since a column header is painted on a {@code .Text} sink
     * that never substitutes a parameter; {@link StatColumnDef#ascending()} ranks a best that shrinks
     * (a Min stat) lowest first.
     */
    @Nullable
    StatColumnDef column(@Nonnull String line);
}
