package com.ziggfreed.common.instance.result;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * One player's line in a {@link TeamResult}: the player's uuid, a headline score, an
 * ordered list of {@link ScoreColumn}s (the breakdown), and the viewer/MVP flags the
 * page uses to highlight + medal-tint. The username is resolved live at render time, not
 * stored here.
 */
public record PlayerResultRow(@Nonnull UUID uuid, long primaryScore, @Nonnull List<ScoreColumn> columns,
                              boolean isViewer, boolean isMvp) {

    public PlayerResultRow {
        columns = List.copyOf(columns);
    }
}
