package com.ziggfreed.common.instance.leaderboard;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.instance.result.ColumnFormat;

/**
 * One column of the {@link LeaderboardPage} Stats view: the {@link LeaderboardEntry} stat key
 * to read, a client-resolved header label, and how to format the value (reusing the results
 * {@link ColumnFormat}). The consumer supplies the ordered list (Kweebec: Stunned / Moonbloom /
 * Shrines over the keys "stunned" / "moonbloom" / "shrines"); the page stays mod-agnostic.
 *
 * <p>{@code ascending} ranks the column lowest-first, for a best that shrinks (a fastest time);
 * an absent value then sorts last ({@link SortMode#byStat(String, boolean)}).
 *
 * <p>A column with {@code parts} is a sum read as the page builds ({@link #summing}): its value is the
 * sum of those stat keys' values that are over {@code partsOver}, never a stored number, and its
 * {@code statKey} only names it (a sort's key), so it must not equal a stored key.
 *
 * <p>The stats-row {@code .ui} ships a fixed number of generic cells, so only the first
 * {@link #MAX_STAT_COLUMNS} columns render (extra columns are dropped).
 */
public record StatColumnDef(@Nonnull String statKey, @Nonnull Message label, @Nonnull ColumnFormat format,
                            boolean ascending, @Nonnull List<String> parts, long partsOver) {

    /** The number of stat cells the stats-row {@code .ui} provides (#Col0..#Col3). */
    public static final int MAX_STAT_COLUMNS = 4;

    public StatColumnDef {
        parts = List.copyOf(parts);
    }

    /** A column reading its own stat key, as every column did before a column could sum others. */
    public StatColumnDef(@Nonnull String statKey, @Nonnull Message label, @Nonnull ColumnFormat format,
                         boolean ascending) {
        this(statKey, label, format, ascending, List.of(), 0L);
    }

    /** A column ranked highest-first, as every column was before a column could rank the other way. */
    public StatColumnDef(@Nonnull String statKey, @Nonnull Message label, @Nonnull ColumnFormat format) {
        this(statKey, label, format, false);
    }

    /** Convenience: a thousands-grouped stat column. */
    @Nonnull
    public static StatColumnDef grouped(@Nonnull String statKey, @Nonnull Message label) {
        return new StatColumnDef(statKey, label, ColumnFormat.GROUPED);
    }

    /**
     * A column whose value is the sum of {@code parts}' values over {@code partsOver} (a group's lines,
     * each counted only past the value every player starts at), ranked highest-first. {@code key} names the
     * column and must not be a stored key.
     */
    @Nonnull
    public static StatColumnDef summing(@Nonnull String key, @Nonnull Message label, @Nonnull ColumnFormat format,
                                        @Nonnull List<String> parts, long partsOver) {
        return new StatColumnDef(key, label, format, false, parts, partsOver);
    }

    /** This column's value on {@code entry}: its stat key's, or the sum of its parts over {@code partsOver}. */
    public long valueOf(@Nonnull LeaderboardEntry entry) {
        if (parts.isEmpty()) {
            return entry.stat(statKey);
        }
        long sum = 0L;
        for (String part : parts) {
            long value = entry.stat(part);
            if (value > partsOver) {
                sum += value;
            }
        }
        return sum;
    }
}
