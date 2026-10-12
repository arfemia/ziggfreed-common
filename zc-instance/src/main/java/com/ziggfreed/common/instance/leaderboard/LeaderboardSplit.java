package com.ziggfreed.common.instance.leaderboard;

import java.util.List;
import java.util.function.BiPredicate;

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
 */
public record LeaderboardSplit(@Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                               @Nonnull List<Choice> choices, @Nonnull BiPredicate<PlayerRef, String> shows,
                               @Nullable String pinnedTab) {

    /** Every pick shows to every viewer. */
    public static final BiPredicate<PlayerRef, String> EVERYONE = (viewer, pick) -> true;

    public LeaderboardSplit {
        choices = List.copyOf(choices);
    }

    /**
     * One group: its key (never empty, which is the first choice), its name, its columns (sums over its picks)
     * and its picks.
     */
    public record Choice(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns,
                         @Nonnull List<Pick> picks) {

        public Choice {
            columns = List.copyOf(columns);
            picks = List.copyOf(picks);
        }
    }

    /** One thing to rank alone: its key (never empty), its name and its columns. */
    public record Pick(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns) {

        public Pick {
            columns = List.copyOf(columns);
        }
    }
}
