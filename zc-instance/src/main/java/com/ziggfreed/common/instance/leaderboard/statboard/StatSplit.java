package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * How a section's tab splits on Records, registered in code by whoever owns the lines
 * ({@link StatSectionConfig#split}): a row of choices under the tab, the first its own columns and then one per
 * group, and a picker of single things. A split names stats with a line per thing ({@code Kills/WOLF});
 * a group's columns are each stat's lines summed over the group's things as the page builds, never stored, and a
 * thing's columns are its own lines. Every line of a split stat is ranked, as a {@code Stat/*} line's are. With
 * nothing registered a tab has no split, which is every tab on a server running the library alone.
 *
 * <p>A split whose columns hold all-time numbers alone (a gauge, such as a level) may name what it ranks on every
 * other period ({@link #offAllTime}): {@code periodic} stats counted into every window (XP gained), read the same
 * way (the first choice their totals, a group their summed lines, a thing its own line), and a {@code meta} figure
 * read from All Time and shown beside each player's name there. With none, such a split reads All Time alone.
 *
 * @param columns      the split stats, each one column on a group or a thing, in order: the first ranks, the rest
 *                     break its ties
 * @param allLabel     the first choice's name: the tab's own columns
 * @param pickAllLabel the picker's first entry, the chosen choice's own columns: a translation, since a dropdown
 *                     entry resolves only a key
 * @param groups       the groups and their things, asked each time Records builds, so a thing added or taken away
 *                     since shows at the next open
 * @param shows        whether a viewer sees a thing (one they may rank in); a group whose things they see none of is
 *                     hidden from them
 * @param periodic     the stats ranked on every period but All Time, in the same shape as {@code columns}; empty,
 *                     the default, ranks {@code columns} everywhere
 * @param meta         the All Time figure shown beside each name on every period but All Time, or null for none
 */
public record StatSplit(@Nonnull List<Column> columns, @Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                        @Nonnull Supplier<List<Group>> groups, @Nonnull BiPredicate<PlayerRef, String> shows,
                        @Nonnull List<Column> periodic, @Nullable Meta meta) {

    public StatSplit {
        columns = List.copyOf(columns);
        periodic = List.copyOf(periodic);
    }

    /** A split that ranks the same columns on every period. */
    public StatSplit(@Nonnull List<Column> columns, @Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                     @Nonnull Supplier<List<Group>> groups, @Nonnull BiPredicate<PlayerRef, String> shows) {
        this(columns, allLabel, pickAllLabel, groups, shows, List.of(), null);
    }

    /**
     * A copy that ranks {@code periodic} on every period but All Time, with {@code meta} beside each name there
     * (null for none). On the first choice a periodic stat reads its total, and the tab's own columns that count
     * into a window follow it. Everything else is kept.
     */
    @Nonnull
    public StatSplit offAllTime(@Nonnull List<Column> periodic, @Nullable Meta meta) {
        return new StatSplit(columns, allLabel, pickAllLabel, groups, shows, periodic, meta);
    }

    /**
     * One split stat's column: its stat id, the column's name on a group and on a thing (a bare translation, as
     * every column header is), and the value a line must pass to count toward a group's sum (1 for a value every
     * player starts at, 0 to count every line).
     */
    public record Column(@Nonnull String stat, @Nonnull Message label, long partsOver) {
    }

    /**
     * The figure a periodic view shows beside each player's name, read from their All Time numbers: stat
     * {@code stat}'s total on the first choice, its lines over {@code partsOver} summed on a group, a thing's own
     * line on a pick. {@code words} turns the figure into the words shown, a translation binding it as a typed number
     * (such as "Lv {0, number}"), never text the server spells.
     */
    public record Meta(@Nonnull String stat, long partsOver, @Nonnull LongFunction<Message> words) {
    }

    /** One group: its id (the choice's key), its name and its things, in order. */
    public record Group(@Nonnull String id, @Nonnull Message label, @Nonnull List<Thing> things) {

        public Group {
            things = List.copyOf(things);
        }
    }

    /** One thing: its id, the {@code Thing} of its lines ({@code WOLF}), and its name. */
    public record Thing(@Nonnull String id, @Nonnull Message label) {
    }
}
