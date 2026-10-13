package com.ziggfreed.common.instance.leaderboard;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.LongFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A primary tab's own split of its board ({@link LeaderboardPageDeps#withSplits}): a row of choices under the
 * tab, its first choice the tab's own columns, then one per group of things, and a picker of single
 * things, each with its own columns, read from the same buckets. The page shows the row only on
 * a tab that has a split.
 *
 * @param allLabel     the first choice's name: the tab's own columns
 * @param pickAllLabel the picker's first entry: the chosen choice's own columns; a translation, since a
 *                     dropdown entry resolves only a key
 * @param choices      the groups after the first choice, in order
 * @param shows        whether a viewer sees a pick (one they may rank in); a choice whose picks they see none
 *                     of is hidden from them
 * @param pinnedTab    the second row's tab every choice but the first reads, its row hidden, for lines that
 *                     only ever hold all-time numbers; null keeps the row
 * @param periods      how the split reads on every second-row tab but its all-time one, or null to read the same
 *                     columns on every tab
 */
public record LeaderboardSplit(@Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                               @Nonnull List<Choice> choices, @Nonnull BiPredicate<PlayerRef, String> shows,
                               @Nullable String pinnedTab, @Nullable Periods periods) {

    /** Every pick shows to every viewer. */
    public static final BiPredicate<PlayerRef, String> EVERYONE = (viewer, pick) -> true;

    public LeaderboardSplit {
        choices = List.copyOf(choices);
    }

    /** A split that reads the same columns on every second-row tab. */
    public LeaderboardSplit(@Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                            @Nonnull List<Choice> choices, @Nonnull BiPredicate<PlayerRef, String> shows,
                            @Nullable String pinnedTab) {
        this(allLabel, pickAllLabel, choices, shows, pinnedTab, null);
    }

    /**
     * How a split reads on every second-row tab but {@code allTimeTab} (a week, a month, a season): the first choice
     * shows {@code first}, a choice or a pick its own {@link Periodic}, and each row names its player with the
     * selection's meta beside them, read from the {@code allTimeTab} bucket and worded by {@code words} (null shows no
     * figure). A null {@code allTimeTab} (a row holding no all-time tab) reads every tab this way, with no figure
     * beside the name.
     */
    public record Periods(@Nullable String allTimeTab, @Nonnull Periodic first,
                          @Nullable LongFunction<Message> words) {
    }

    /**
     * What a selection shows off its all-time tab: its columns (empty keeps the ones it shows there) and the column
     * the figure beside each name reads on the all-time bucket, or null for none.
     */
    public record Periodic(@Nonnull List<StatColumnDef> columns, @Nullable StatColumnDef meta) {

        /** Nothing of its own: the selection's all-time columns and no figure beside the name. */
        public static final Periodic NONE = new Periodic(List.of(), null);

        public Periodic {
            columns = List.copyOf(columns);
        }
    }

    /**
     * One group: its key (never empty, which is the first choice), its name, its columns (sums over its picks),
     * its picks and what it shows off the all-time tab ({@link Periods}).
     */
    public record Choice(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns,
                         @Nonnull List<Pick> picks, @Nonnull Periodic periodic) {

        public Choice {
            columns = List.copyOf(columns);
            picks = List.copyOf(picks);
        }

        /** A group showing the same columns on every tab. */
        public Choice(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns,
                      @Nonnull List<Pick> picks) {
            this(key, label, columns, picks, Periodic.NONE);
        }
    }

    /** One thing to rank alone: its key (never empty), its name, its columns and what it shows off the all-time tab. */
    public record Pick(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns,
                       @Nonnull Periodic periodic) {

        public Pick {
            columns = List.copyOf(columns);
        }

        /** A thing showing the same columns on every tab. */
        public Pick(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns) {
            this(key, label, columns, Periodic.NONE);
        }
    }
}
