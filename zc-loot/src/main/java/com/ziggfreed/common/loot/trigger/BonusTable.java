package com.ziggfreed.common.loot.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.match.MomentTable;
import com.ziggfreed.common.match.NamePattern;

/**
 * Which row covers a name in a moment: the fold and the most-specific match every bonus table
 * shares. Immutable; build a new one per fold.
 *
 * <p>Rows fold per moment by their pattern, so two rows claiming one pattern in one moment are ONE
 * answer: the later row in fold order wins it and takes the earlier one's place. Matching is the
 * shared name grammar scored by the shared specificity ladder: an exact pattern first, then the
 * pattern pinning down the longest run of characters, then the bare {@code "*"} catch-all; two
 * equally specific patterns keep the one folded first. All of it is zc-core's {@link MomentTable}, keyed
 * by each moment's objective kind id ({@link BonusMoment#kindId()}), which also keeps each answer per name.
 *
 * @param <E> the table's own row type
 */
public final class BonusTable<E extends BonusEntry> {

    /** One row as authored for the fold: its parsed pattern, the key rows collapse on, the row. */
    public record Row<R extends BonusEntry>(@Nonnull NamePattern pattern, @Nonnull String patternKey,
            @Nonnull R entry) {

        /** The row for {@code match} as authored; blank or missing means everything in its moment. */
        @Nonnull
        public static <R extends BonusEntry> Row<R> of(@Nullable String match, @Nonnull R entry) {
            String authored = match == null ? "" : match.trim();
            return new Row<>(NamePattern.parse(authored.isEmpty() ? "*" : authored),
                    authored.toLowerCase(Locale.ROOT), entry);
        }
    }

    @Nonnull
    private final MomentTable<Row<E>> table;

    private BonusTable(@Nonnull MomentTable<Row<E>> table) {
        this.table = table;
    }

    /** The table {@code inFoldOrder} folds to. */
    @Nonnull
    public static <E extends BonusEntry> BonusTable<E> fold(@Nonnull List<Row<E>> inFoldOrder) {
        List<MomentTable.Row<Row<E>>> rows = new ArrayList<>(inFoldOrder.size());
        for (Row<E> row : inFoldOrder) {
            rows.add(new MomentTable.Row<>(row.entry().moment().kindId(), row.pattern(), null, row.patternKey(),
                    row));
        }
        return new BonusTable<>(MomentTable.fold(rows));
    }

    /**
     * The row covering {@code name} in {@code moment}, or null when nothing covers it (or the name
     * is blank). Case is ignored.
     */
    @Nullable
    public E bestFor(@Nonnull BonusMoment moment, @Nullable String name) {
        Row<E> row = table.bestFor(moment.kindId(), name, null);
        return row == null ? null : row.entry();
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

    /** One moment's rows paired with the patterns they match on, in fold order (the audit's walk). */
    @Nonnull
    public List<Map.Entry<NamePattern, E>> patterned(@Nonnull BonusMoment moment) {
        List<Map.Entry<NamePattern, E>> out = new ArrayList<>();
        for (MomentTable.Row<Row<E>> row : table.rows(moment.kindId())) {
            out.add(Map.entry(row.entry().pattern(), row.entry().entry()));
        }
        return out;
    }

    /** How many rows the table holds across every moment. */
    public int size() {
        return table.size();
    }
}
