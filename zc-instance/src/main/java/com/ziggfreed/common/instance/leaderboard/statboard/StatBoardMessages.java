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
 * shared words all the same, since the page asks for them. The page paints every line on a
 * {@code .TextSpans}, so a line may carry parameters.
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

    /**
     * The line under a tab that names none of its own ({@code HintKey}): what counts and how to get on the
     * board, said for any columns.
     */
    @Nonnull
    public static Message hint() {
        return line("hint");
    }

    @Nonnull
    private static Message shared(@Nonnull String key) {
        return EncounterLeaderboardMessages.line(key);
    }

    @Override @Nonnull public Message title() {
        return line("title");
    }

    /** How to read the board: everyone ranked, a click on a column ranks by it. */
    @Override @Nonnull public Message lead() {
        return line("lead");
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

    /** A playtime in hours and minutes ("12h 30m"), or minutes alone under an hour ("45m"). */
    @Override @Nonnull public Message duration(long seconds) {
        long s = Math.max(0L, seconds);
        long hours = s / 3600L;
        long minutes = (s % 3600L) / 60L;
        return hours > 0L ? line("duration.hours", hours, minutes) : line("duration.minutes", minutes);
    }
}
