package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.instance.result.ColumnFormat;

/**
 * One column of the {@link LeaderboardPage} Stats view: the {@link LeaderboardEntry} stat key
 * to read, a client-resolved header label, and how to format the value (reusing the results
 * {@link ColumnFormat}). The consumer supplies the ordered list (Kweebec: Stunned / Moonbloom /
 * Shrines over the keys "stunned" / "moonbloom" / "shrines"); the page stays mod-agnostic.
 *
 * <p>The stats-row {@code .ui} ships a fixed number of generic cells, so only the first
 * {@link #MAX_STAT_COLUMNS} columns render (extra columns are dropped).
 */
public record StatColumnDef(@Nonnull String statKey, @Nonnull Message label, @Nonnull ColumnFormat format) {

    /** The number of stat cells the stats-row {@code .ui} provides (#Col0..#Col3). */
    public static final int MAX_STAT_COLUMNS = 4;

    /** Convenience: a thousands-grouped stat column. */
    @Nonnull
    public static StatColumnDef grouped(@Nonnull String statKey, @Nonnull Message label) {
        return new StatColumnDef(statKey, label, ColumnFormat.GROUPED);
    }
}
