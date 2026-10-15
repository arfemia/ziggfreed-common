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
 * a tab that has a split. A picked thing with variants ({@link Pick#variants}, a boss's party sizes) adds a row of
 * them under it: the first, {@link #variantAllLabel}, the thing's own columns, then each variant's.
 *
 * @param allLabel        the first choice's name: the tab's own columns
 * @param pickAllLabel    the picker's first entry: the chosen choice's own columns; a translation, since a
 *                        dropdown entry resolves only a key
 * @param choices         the groups after the first choice, in order
 * @param shows           whether a viewer sees a pick (one they may rank in); a choice whose picks they see none
 *                        of is hidden from them
 * @param pinnedTab       the second row's tab every choice but the first reads, its row hidden, for lines that
 *                        only ever hold all-time numbers; null keeps the row
 * @param periods         how the split reads on every second-row tab but its all-time one, or null to read the same
 *                        columns on every tab
 * @param ungrouped       the picks in no group, offered under the first choice alone, after every choice's
 * @param variantAllLabel the first segment of a pick's variants (the pick's own columns), or null for the board's
 *                        own All ({@link LeaderboardScreenMessages#filterAll})
 */
public record LeaderboardSplit(@Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                               @Nonnull List<Choice> choices, @Nonnull BiPredicate<PlayerRef, String> shows,
                               @Nullable String pinnedTab, @Nullable Periods periods, @Nonnull List<Pick> ungrouped,
                               @Nullable Message variantAllLabel) {

    /** Every pick shows to every viewer. */
    public static final BiPredicate<PlayerRef, String> EVERYONE = (viewer, pick) -> true;

    public LeaderboardSplit {
        choices = List.copyOf(choices);
        ungrouped = List.copyOf(ungrouped);
    }

    /** A split whose every pick sits in a group, with no variants' label of its own. */
    public LeaderboardSplit(@Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                            @Nonnull List<Choice> choices, @Nonnull BiPredicate<PlayerRef, String> shows,
                            @Nullable String pinnedTab, @Nullable Periods periods) {
        this(allLabel, pickAllLabel, choices, shows, pinnedTab, periods, List.of(), null);
    }

    /** A split that reads the same columns on every second-row tab. */
    public LeaderboardSplit(@Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                            @Nonnull List<Choice> choices, @Nonnull BiPredicate<PlayerRef, String> shows,
                            @Nullable String pinnedTab) {
        this(allLabel, pickAllLabel, choices, shows, pinnedTab, null);
    }

    /**
     * The choice that offers pick {@code pickKey}, for a link that opens the split on it: the first group holding it,
     * the empty key (the first choice) for a pick in no group, or null when the split offers no such pick.
     */
    @Nullable
    public String choiceOf(@Nonnull String pickKey) {
        for (Choice choice : choices) {
            for (Pick pick : choice.picks()) {
                if (pick.key().equals(pickKey)) {
                    return choice.key();
                }
            }
        }
        for (Pick pick : ungrouped) {
            if (pick.key().equals(pickKey)) {
                return "";
            }
        }
        return null;
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
     * One group: its key (never empty, which is the first choice), its name, its columns (folds over its picks),
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

    /**
     * One thing to rank alone: its key (never empty), its name, its columns, what it shows off the all-time tab, and
     * its variants in order (none shows no row of them).
     */
    public record Pick(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns,
                       @Nonnull Periodic periodic, @Nonnull List<Variant> variants) {

        public Pick {
            columns = List.copyOf(columns);
            variants = List.copyOf(variants);
        }

        /** A thing with no variants. */
        public Pick(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns,
                    @Nonnull Periodic periodic) {
            this(key, label, columns, periodic, List.of());
        }

        /** A thing showing the same columns on every tab. */
        public Pick(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns) {
            this(key, label, columns, Periodic.NONE);
        }
    }

    /**
     * One variant of a pick (a boss's party size): its key (never empty, which is the pick's own columns), its name,
     * painted on a label's {@code TextSpans} so it may bind a typed number, its columns and what it shows off the
     * all-time tab.
     */
    public record Variant(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns,
                          @Nonnull Periodic periodic) {

        public Variant {
            columns = List.copyOf(columns);
        }

        /** A variant showing the same columns on every tab. */
        public Variant(@Nonnull String key, @Nonnull Message label, @Nonnull List<StatColumnDef> columns) {
            this(key, label, columns, Periodic.NONE);
        }
    }
}
