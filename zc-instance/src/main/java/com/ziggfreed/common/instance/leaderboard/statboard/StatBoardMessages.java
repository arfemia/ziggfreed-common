package com.ziggfreed.common.instance.leaderboard.statboard;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardMessages;
import com.ziggfreed.common.instance.leaderboard.LeaderboardScreenMessages;

/**
 * The stat board's words on the records page: its own lines in {@code ziggfreedcommon.statboard.lang},
 * and the column words every records screen shares from {@code ziggfreedcommon.leaderboard.lang}. The
 * board shows the Stats view alone, so the Rankings view's lines are never painted; they resolve to the
 * shared words all the same, since the page asks for them. Every line but the footer is a bare
 * translation, since the page paints them on {@code .Text} sinks.
 */
public final class StatBoardMessages implements LeaderboardScreenMessages {

    /** The key family the board's own lines resolve under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.statboard.";

    /** The one set of words the board needs; it holds no state. */
    public static final StatBoardMessages INSTANCE = new StatBoardMessages();

    private StatBoardMessages() {
    }

    /** A line of the board's own family, as a client-resolved message. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    @Nonnull
    private static Message shared(@Nonnull String key) {
        return EncounterLeaderboardMessages.line(key);
    }

    @Override @Nonnull public Message title() {
        return line("title");
    }

    @Override @Nonnull public Message colRank() {
        return shared("col.rank");
    }

    @Override @Nonnull public Message colPlayer() {
        return shared("col.player");
    }

    @Override @Nonnull public Message colScore() {
        return shared("col.score");
    }

    @Override @Nonnull public Message colTotal() {
        return shared("col.total");
    }

    @Override @Nonnull public Message colTime() {
        return shared("col.time");
    }

    @Override @Nonnull public Message colPlays() {
        return shared("col.plays");
    }

    @Override @Nonnull public Message empty() {
        return line("empty");
    }

    @Override @Nonnull public Message sortScore() {
        return shared("sort.score");
    }

    @Override @Nonnull public Message sortTotal() {
        return shared("sort.total");
    }

    @Override @Nonnull public Message sortTime() {
        return shared("sort.time");
    }

    @Override @Nonnull public Message viewRankings() {
        return shared("view.rankings");
    }

    @Override @Nonnull public Message viewStats() {
        return shared("view.stats");
    }

    @Override @Nonnull public Message filterAll() {
        return shared("filter.all");
    }

    /** The footer: the viewer's place on the selected column; the figure is the column's to show. */
    @Override @Nonnull public Message yourRank(int rank, long value) {
        return line("your_rank", rank, value);
    }

    @Override @Nonnull public Message yourRankNone() {
        return line("your_rank.none");
    }
}
