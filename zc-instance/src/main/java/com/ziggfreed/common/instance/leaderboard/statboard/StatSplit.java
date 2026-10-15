package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;

/**
 * How a section's tab splits on Records, registered in code by whoever owns the lines
 * ({@link StatSectionConfig#split}): a row of choices under the tab, the first its own columns and then one per
 * group, and a picker of single things. A split names stats with a line per thing ({@code Kills/WOLF}); a thing is
 * a named set of those lines ({@link Thing#lines}, its own id by default), and its columns fold its lines; a group's
 * columns fold every line of its things; each fold reads as the page builds, never stored, and folds each stat's
 * lines its own way ({@link StatBoardSource#combineOf}: added up, or the best kept). Every line of a split stat is
 * ranked, as a {@code Stat/*} line's are. With nothing registered a tab has no split, which is every tab on a server
 * running the library alone.
 *
 * <p>A thing may have variants ({@link Variant}, a boss's party sizes): the picked thing then shows a row of them,
 * the first ({@link #variantAllLabel}) the thing's own columns over every line, then each variant's columns over its
 * own lines. A group whose id is blank has no segment of its own: its things are offered under the first choice
 * alone (a boss in no group).
 *
 * <p>A split may leave its columns to its section: with {@code columns} empty, its section's {@code Stat/*} lines are
 * a thing's and a variant's columns and its plain stat ids a group's ({@link #fromSection}), each named as the stat
 * names itself, read as the section's files fold. With {@code groupColumns} empty, a group folds {@code columns}.
 *
 * <p>A split whose columns hold all-time numbers alone (a gauge, such as a level) may name what it ranks on every
 * other period ({@link #offAllTime}): {@code periodic} stats counted into every window (XP gained), read the same
 * way (the first choice their totals, a group their lines folded, a thing its own lines), and a {@code meta} figure
 * read from All Time and shown beside each player's name there. With none, such a split reads All Time alone.
 *
 * @param columns         the split stats, each one column on a thing or a variant, in order: the first ranks, the
 *                        rest break its ties; empty takes the section's {@code Stat/*} lines ({@link #fromSection})
 * @param allLabel        the first choice's name: the tab's own columns
 * @param pickAllLabel    the picker's first entry, the chosen choice's own columns: a translation, since a dropdown
 *                        entry resolves only a key
 * @param groups          the groups and their things, asked each time Records builds, so a thing added or taken away
 *                        since shows at the next open
 * @param shows           whether a viewer sees a thing (one they may rank in); a group whose things they see none of
 *                        is hidden from them
 * @param periodic        the stats ranked on every period but All Time, in the same shape as {@code columns}; empty,
 *                        the default, ranks {@code columns} everywhere
 * @param meta            the All Time figure shown beside each name on every period but All Time, or null for none
 * @param groupColumns    the stats a group folds over its things, one column each; empty, the default, folds
 *                        {@code columns}
 * @param variantAllLabel the first segment of a picked thing's variants, its own columns over every line; null shows
 *                        the board's own All
 */
public record StatSplit(@Nonnull List<Column> columns, @Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                        @Nonnull Supplier<List<Group>> groups, @Nonnull BiPredicate<PlayerRef, String> shows,
                        @Nonnull List<Column> periodic, @Nullable Meta meta, @Nonnull List<Column> groupColumns,
                        @Nullable Message variantAllLabel) {

    public StatSplit {
        columns = List.copyOf(columns);
        periodic = List.copyOf(periodic);
        groupColumns = groupColumns.isEmpty() ? columns : List.copyOf(groupColumns);
    }

    /** A split whose groups fold the same columns its things show, and whose things carry no variants' label. */
    public StatSplit(@Nonnull List<Column> columns, @Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                     @Nonnull Supplier<List<Group>> groups, @Nonnull BiPredicate<PlayerRef, String> shows,
                     @Nonnull List<Column> periodic, @Nullable Meta meta) {
        this(columns, allLabel, pickAllLabel, groups, shows, periodic, meta, List.of(), null);
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
        return new StatSplit(columns, allLabel, pickAllLabel, groups, shows, periodic, meta, groupColumns,
                variantAllLabel);
    }

    /**
     * A copy whose picked thing names the first segment of its variants {@code label} (its own columns over every
     * line), in place of the board's own All. Everything else is kept.
     */
    @Nonnull
    public StatSplit withVariantAllLabel(@Nullable Message label) {
        return new StatSplit(columns, allLabel, pickAllLabel, groups, shows, periodic, meta, groupColumns, label);
    }

    /**
     * This split with its columns read from its section's {@code lines} when it names none of its own: each
     * {@code Stat/*} line's stat a thing's column, and each plain stat id a group's (unless it names group columns
     * of its own), at most {@value StatColumnDef#MAX_STAT_COLUMNS} of each, each named as its stat names itself and
     * counting every line. A split naming its own columns is answered as it is.
     */
    @Nonnull
    public StatSplit fromSection(@Nonnull List<String> lines) {
        if (!columns.isEmpty()) {
            return this;
        }
        List<Column> things = new ArrayList<>();
        List<Column> totals = new ArrayList<>();
        for (String line : lines) {
            String every = StatSection.everyLineOf(line);
            if (every != null) {
                if (!every.isBlank() && things.size() < StatColumnDef.MAX_STAT_COLUMNS) {
                    things.add(new Column(every, null, 0L));
                }
            } else if (line != null && !line.isBlank() && line.indexOf('/') < 0
                    && totals.size() < StatColumnDef.MAX_STAT_COLUMNS) {
                totals.add(new Column(line.trim(), null, 0L));
            }
        }
        // A split's own group columns stand (with no columns of its own, an empty list is none); derived ones
        // only fill what it left to the section.
        return new StatSplit(things, allLabel, pickAllLabel, groups, shows, periodic, meta,
                groupColumns.isEmpty() ? totals : groupColumns, variantAllLabel);
    }

    /**
     * One split stat's column: its stat id, the column's name on a group and on a thing (a bare translation, as
     * every column header is; null takes the name the stat gives itself), and the value a line must pass to count
     * toward a fold (1 for a value every player starts at, 0 to count every line).
     */
    public record Column(@Nonnull String stat, @Nullable Message label, long partsOver) {
    }

    /**
     * The figure a periodic view shows beside each player's name, read from their All Time numbers: stat
     * {@code stat}'s total on the first choice, its lines over {@code partsOver} folded on a group, a thing's own
     * lines on a pick. {@code words} turns the figure into the words shown, a translation binding it as a typed number
     * (such as "Lv {0, number}"), never text the server spells.
     */
    public record Meta(@Nonnull String stat, long partsOver, @Nonnull LongFunction<Message> words) {
    }

    /**
     * One group: its id (the choice's key), its name and its things, in order. A blank id has no segment: its things
     * are offered under the first choice alone.
     */
    public record Group(@Nonnull String id, @Nonnull Message label, @Nonnull List<Thing> things) {

        public Group {
            things = List.copyOf(things);
        }
    }

    /**
     * One thing: its id (the pick's key), its name, the {@code Thing}s of the lines its columns fold (each read
     * {@code Stat/<line>}; empty is its own id alone, {@code WOLF}) and its variants, in order (none shows no row of
     * them).
     */
    public record Thing(@Nonnull String id, @Nonnull Message label, @Nonnull List<String> lines,
                        @Nonnull List<Variant> variants) {

        public Thing {
            lines = lines.isEmpty() ? List.of(id) : List.copyOf(lines);
            variants = List.copyOf(variants);
        }

        /** A thing whose one line is its own id, with no variants. */
        public Thing(@Nonnull String id, @Nonnull Message label) {
            this(id, label, List.of(), List.of());
        }
    }

    /**
     * One variant of a thing (a boss's party size): its id (the variant's key), its name (painted on a label's
     * {@code TextSpans}, so it may bind a typed number), and the {@code Thing}s of the lines its columns fold.
     */
    public record Variant(@Nonnull String id, @Nonnull Message label, @Nonnull List<String> lines) {

        public Variant {
            lines = List.copyOf(lines);
        }
    }
}
