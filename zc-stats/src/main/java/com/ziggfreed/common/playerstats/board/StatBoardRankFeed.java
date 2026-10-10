package com.ziggfreed.common.playerstats.board;

import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.playerstats.StatRankFeed;

/**
 * The stat store's writes, ranked on zc-instance's stat board: each move is the board's own of the same
 * name, and the board keeps only the lines some section names and decides which periods a write reaches.
 */
public final class StatBoardRankFeed implements StatRankFeed {

    @Override
    public void add(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> deltas) {
        StatBoard.add(uuid, name, deltas);
    }

    @Override
    public void raise(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values) {
        StatBoard.raise(uuid, name, values);
    }

    @Override
    public void lower(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values) {
        StatBoard.lower(uuid, name, values);
    }

    @Override
    public void setAllTime(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values) {
        StatBoard.setAllTime(uuid, name, values);
    }
}
