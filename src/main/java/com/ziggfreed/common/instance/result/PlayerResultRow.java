package com.ziggfreed.common.instance.result;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * One player's line in a {@link TeamResult}: the player's uuid, a headline score, the
 * point-breakdown {@link ScoreColumn}s ({@code columns}), an optional run-stats list
 * ({@code statColumns} - raw per-run activity rendered as a second section), and the
 * viewer/MVP flags the page uses to highlight + medal-tint. The username is resolved live
 * at render time, not stored here.
 */
public record PlayerResultRow(@Nonnull UUID uuid, long primaryScore, @Nonnull List<ScoreColumn> columns,
                              @Nonnull List<ScoreColumn> statColumns, boolean isViewer, boolean isMvp) {

    public PlayerResultRow {
        columns = List.copyOf(columns);
        statColumns = List.copyOf(statColumns);
    }

    /** Back-compat: a row with only a point breakdown (no separate run-stats section). */
    public PlayerResultRow(@Nonnull UUID uuid, long primaryScore, @Nonnull List<ScoreColumn> columns,
                           boolean isViewer, boolean isMvp) {
        this(uuid, primaryScore, columns, List.of(), isViewer, isMvp);
    }
}
