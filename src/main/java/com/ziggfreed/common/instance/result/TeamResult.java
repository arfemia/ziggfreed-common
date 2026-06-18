package com.ziggfreed.common.instance.result;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One team's section on the results screen: a label, a team total, a rank (for multi-team
 * modes), and the per-player rows. For a co-op / free-for-all instance there is ONE team
 * containing the whole party (the single-team default) so per-player ranking still works;
 * raids / team-PvP get multi-team for free.
 */
public record TeamResult(@Nonnull String teamId, @Nullable Message teamLabel, long teamTotal, int rank,
                         @Nonnull List<PlayerResultRow> rows) {

    public TeamResult {
        rows = List.copyOf(rows);
    }

    /** A single team (the co-op default) holding all players; no team label, rank 1. */
    @Nonnull
    public static TeamResult single(long teamTotal, @Nonnull List<PlayerResultRow> rows) {
        return new TeamResult("party", null, teamTotal, 1, rows);
    }
}
