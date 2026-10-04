package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * The library's own words for a boss fight's records screen, so the screen reads on a server running
 * no consumer of its own. Every line is a key in {@code ziggfreedcommon.leaderboard.lang}, resolved on
 * the reader's client; the screen's title is the fight's own name (its binding row's {@code NameKey})
 * when it has one.
 *
 * <p>The page paints every line but the footer on a {@code .Text} sink, which resolves a translation
 * but never substitutes a parameter, so every line here but {@link #yourRank} is a bare translation.
 */
public final class EncounterLeaderboardMessages implements LeaderboardScreenMessages {

    /** The key family every line resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.leaderboard.";

    @Nullable private final String nameKey;

    /** @param nameKey the fight's own name key (its binding row's {@code NameKey}), or null for the generic title */
    public EncounterLeaderboardMessages(@Nullable String nameKey) {
        this.nameKey = nameKey == null || nameKey.isBlank() ? null : nameKey;
    }

    /** A line of this family as a client-resolved message, for the screen or a command reply. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    @Override @Nonnull public Message title() {
        return nameKey != null ? Message.translation(nameKey) : line("title");
    }

    @Override @Nonnull public Message primaryAxisLabel() {
        return line("axis.difficulty");
    }

    @Override @Nonnull public Message secondaryAxisLabel() {
        return line("axis.players");
    }

    @Override @Nonnull public Message sortLabel() {
        return line("axis.sort");
    }

    @Override @Nonnull public Message viewLabel() {
        return line("axis.view");
    }

    @Override @Nonnull public Message colRank() {
        return line("col.rank");
    }

    @Override @Nonnull public Message colPlayer() {
        return line("col.player");
    }

    @Override @Nonnull public Message colScore() {
        return line("col.score");
    }

    @Override @Nonnull public Message colTotal() {
        return line("col.total");
    }

    @Override @Nonnull public Message colTime() {
        return line("col.time");
    }

    @Override @Nonnull public Message colPlays() {
        return line("col.plays");
    }

    @Override @Nonnull public Message empty() {
        return line("empty");
    }

    @Override @Nonnull public Message sortScore() {
        return line("sort.score");
    }

    @Override @Nonnull public Message sortTotal() {
        return line("sort.total");
    }

    @Override @Nonnull public Message sortTime() {
        return line("sort.time");
    }

    @Override @Nonnull public Message viewRankings() {
        return line("view.rankings");
    }

    @Override @Nonnull public Message viewStats() {
        return line("view.stats");
    }

    @Override @Nonnull public Message filterAll() {
        return line("filter.all");
    }

    /** The one parameterized line: the rank and the figure bind as typed numbers. */
    @Override @Nonnull public Message yourRank(int rank, long bestScore) {
        return line("your_rank", rank, bestScore);
    }

    @Override @Nonnull public Message yourRankNone() {
        return line("your_rank.none");
    }

    /** The Stats view's damage-dealt column header. */
    @Nonnull
    public Message statDamageDealt() {
        return line("stat.damage_dealt");
    }

    /** The Stats view's damage-taken column header. */
    @Nonnull
    public Message statDamageTaken() {
        return line("stat.damage_taken");
    }
}
