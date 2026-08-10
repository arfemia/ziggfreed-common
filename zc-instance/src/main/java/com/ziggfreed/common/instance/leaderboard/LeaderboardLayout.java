package com.ziggfreed.common.instance.leaderboard;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The normalized runtime form of a {@link LeaderboardLayoutAsset}: the pack-authored leaderboard
 * layout with every display label already resolved to a client-resolved {@link Message}. It is the
 * data a consumer was previously declaring as Java literals at startup (Kweebec's
 * {@code KweebecExperience.init}: the {@code board id} + the difficulty / party-size tab axes + the
 * Stats-view columns), now lifted to a pack asset.
 *
 * <p>Because common CAN build a {@link Message} from a key (via {@link Message#translation(String)},
 * the same path {@code DialogueMessages} / {@code Lang.msg} use), this resolved layout hands the page
 * deps READY {@link LeaderboardBucketTab}s + {@link StatColumnDef}s - the consumer does NOT map
 * keys to {@link Message}s itself. A consumer builds its {@link LeaderboardPageDeps} straight from
 * the accessors here (passing its own {@link Leaderboard} + {@link LeaderboardScreenMessages} chrome).
 *
 * <p>The {@code primaryTabs} are the difficulty axis: each tab's {@link LeaderboardBucketTab#bucketKey()}
 * is the preset id, and the recorder + CTA compose the leaderboard bucket as
 * {@code "<difficulty>_<partySize>"}. Empty {@code primaryTabs} / {@code statColumns} degrade the page
 * to the original single-axis, score-only board.
 *
 * @param id            the layout id (lowercase, the {@code defaults < pack < owner} fold key)
 * @param boardId       the {@link Leaderboard} store id this layout reads (one board per game-mode)
 * @param primaryAxisLabel   the PRIMARY (difficulty) selector-row label, or {@code null} to hide it
 * @param secondaryAxisLabel the SECONDARY (party-size) selector-row label, or {@code null} to hide it
 * @param primaryTabs   the difficulty tab axis (bucket key = preset id); empty for a single-axis board
 * @param secondaryTabs the party-size tab axis
 * @param statColumns   the Stats-view columns; empty disables the Stats view
 */
public record LeaderboardLayout(@Nonnull String id, @Nonnull String boardId,
                                @Nullable Message primaryAxisLabel, @Nullable Message secondaryAxisLabel,
                                @Nonnull List<LeaderboardBucketTab> primaryTabs,
                                @Nonnull List<LeaderboardBucketTab> secondaryTabs,
                                @Nonnull List<StatColumnDef> statColumns) {

    public LeaderboardLayout {
        primaryTabs = List.copyOf(primaryTabs);
        secondaryTabs = List.copyOf(secondaryTabs);
        statColumns = List.copyOf(statColumns);
    }
}
