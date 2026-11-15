package com.ziggfreed.common.loot.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.match.MatchKey;
import com.ziggfreed.common.match.MomentTable;

/**
 * Which row covers a name in a moment: the fold and the most-specific match every bonus table
 * shares. Immutable; build a new one per fold.
 *
 * <p>Rows fold per moment by their key, so two rows claiming one key in one moment are ONE
 * answer: the later row in fold order wins it and takes the earlier one's place. A key is zc-core's
 * {@link MatchKey}: a name pattern, or a set the block, mob or item's own asset puts it in
 * ({@code ItemTag:}, {@code BlockFamily:}, {@code NpcGroup:}). Matching is scored by the shared
 * specificity ladder: an exact pattern first, then the pattern pinning down the longest run of
 * characters, then any set key, then the bare {@code "*"} catch-all; two equally specific keys keep
 * the one folded first. All of it is zc-core's {@link MomentTable}, keyed by each moment's objective
 * kind id ({@link BonusMoment#kindId()}), which also keeps each answer per name.
 *
 * <p><b>Groups.</b> A row naming a {@linkplain BonusEntry#group() group} competes only with the rows of
 * that group: they fold and rank among themselves, and each group gives its own answer
 * ({@link #answers}), so a moment may pay one row from each group. A table whose rows name no group
 * has the shared group alone and answers exactly as before.
 *
 * @param <E> the table's own row type
 */
public final class BonusTable<E extends BonusEntry> {

    /** One row as authored for the fold: its parsed key, the key rows collapse on, the row. */
    public record Row<R extends BonusEntry>(@Nonnull MatchKey pattern, @Nonnull String patternKey,
            @Nonnull R entry) {

        /** The row for {@code match} as authored; blank or missing means everything in its moment. */
        @Nonnull
        public static <R extends BonusEntry> Row<R> of(@Nullable String match, @Nonnull R entry) {
            MatchKey key = MatchKey.parse(match);
            return new Row<>(key, key.foldKey(), entry);
        }
    }

    @Nonnull
    private final MomentTable<Row<E>> table;

    /** Does any row name a group of its own? Only then does an answer walk the ladder for each group. */
    private final boolean grouped;

    private BonusTable(@Nonnull MomentTable<Row<E>> table, boolean grouped) {
        this.table = table;
        this.grouped = grouped;
    }

    /** The table {@code inFoldOrder} folds to. */
    @Nonnull
    public static <E extends BonusEntry> BonusTable<E> fold(@Nonnull List<Row<E>> inFoldOrder) {
        List<MomentTable.Row<Row<E>>> rows = new ArrayList<>(inFoldOrder.size());
        boolean grouped = false;
        for (Row<E> row : inFoldOrder) {
            String group = groupOf(row.entry());
            grouped |= !group.isEmpty();
            // A group's rows collapse among themselves only, so the group rides the fold key.
            String foldKey = group.isEmpty() ? row.patternKey() : group + '\u0001' + row.patternKey();
            rows.add(new MomentTable.Row<>(row.entry().moment().kindId(), row.pattern(), null, foldKey, row));
        }
        return new BonusTable<>(MomentTable.fold(rows), grouped);
    }

    /**
     * The row of the shared group covering {@code name} in {@code moment}, or null when nothing there
     * covers it (or the name is blank). Case is ignored. A table whose rows name no group has the
     * shared group alone; {@link #answers} gives every group's.
     */
    @Nullable
    public E bestFor(@Nonnull BonusMoment moment, @Nullable String name) {
        if (!grouped) {
            Row<E> row = table.bestFor(moment.kindId(), name, null);
            return row == null ? null : row.entry();
        }
        for (Row<E> row : table.matching(moment.kindId(), name, null)) {
            if (groupOf(row.entry()).isEmpty()) {
                return row.entry();
            }
        }
        return null;
    }

    /**
     * Every group's row covering {@code name} in {@code moment}: the most specific of each group, the
     * shared group's first, then the others by group id. Empty when nothing covers the name (or it is
     * blank). Case is ignored. A table whose rows name no group answers at most one row, its
     * {@link #bestFor}.
     */
    @Nonnull
    public List<E> answers(@Nonnull BonusMoment moment, @Nullable String name) {
        if (!grouped) {
            E best = bestFor(moment, name);
            return best == null ? List.of() : List.of(best);
        }
        List<Row<E>> ladder = table.matching(moment.kindId(), name, null);
        if (ladder.isEmpty()) {
            return List.of();
        }
        // The ladder is most specific first, so the first row met of each group is that group's answer.
        Map<String, E> byGroup = new TreeMap<>();
        for (Row<E> row : ladder) {
            byGroup.putIfAbsent(groupOf(row.entry()), row.entry());
        }
        return List.copyOf(byGroup.values());
    }

    /** Every folded row: the moments in declaration order, each in fold order. */
    @Nonnull
    public List<E> entries() {
        List<E> out = new ArrayList<>();
        for (BonusMoment moment : BonusMoment.values()) {
            for (MomentTable.Row<Row<E>> row : table.rows(moment.kindId())) {
                out.add(row.entry().entry());
            }
        }
        return out;
    }

    /** One moment's rows paired with the keys they match on, in fold order (the audit's walk). */
    @Nonnull
    public List<Map.Entry<MatchKey, E>> patterned(@Nonnull BonusMoment moment) {
        List<Map.Entry<MatchKey, E>> out = new ArrayList<>();
        for (MomentTable.Row<Row<E>> row : table.rows(moment.kindId())) {
            out.add(Map.entry(row.entry().pattern(), row.entry().entry()));
        }
        return out;
    }

    /** How many rows the table holds across every moment. */
    public int size() {
        return table.size();
    }

    /** A row's group as the fold reads it: never null, blank for the shared one. */
    @Nonnull
    private static String groupOf(@Nonnull BonusEntry entry) {
        String group = entry.group();
        return group == null ? "" : group;
    }
}
