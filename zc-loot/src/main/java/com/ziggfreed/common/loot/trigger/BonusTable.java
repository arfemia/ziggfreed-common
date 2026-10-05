package com.ziggfreed.common.loot.trigger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.match.NameMatchRank;
import com.ziggfreed.common.match.NamePattern;

/**
 * Which row covers a name in a moment: the fold and the most-specific match every bonus table
 * shares. Immutable; build a new one per fold.
 *
 * <p>Rows fold per moment by their pattern, so two rows claiming one pattern in one moment are ONE
 * answer: the later row in fold order wins it and takes the earlier one's place. Matching is the
 * shared name grammar scored by the shared specificity ladder: an exact pattern first, then the
 * pattern pinning down the longest run of characters, then the bare {@code "*"} catch-all; two
 * equally specific patterns keep the one folded first.
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

    private final Map<BonusMoment, List<Row<E>>> rows;

    private BonusTable(@Nonnull Map<BonusMoment, List<Row<E>>> rows) {
        this.rows = rows;
    }

    /** The table {@code inFoldOrder} folds to. */
    @Nonnull
    public static <E extends BonusEntry> BonusTable<E> fold(@Nonnull List<Row<E>> inFoldOrder) {
        Map<BonusMoment, Map<String, Row<E>>> folded = new EnumMap<>(BonusMoment.class);
        for (Row<E> row : inFoldOrder) {
            folded.computeIfAbsent(row.entry().moment(), moment -> new LinkedHashMap<>())
                    .put(row.patternKey(), row);
        }
        Map<BonusMoment, List<Row<E>>> built = new EnumMap<>(BonusMoment.class);
        for (Map.Entry<BonusMoment, Map<String, Row<E>>> e : folded.entrySet()) {
            built.put(e.getKey(), List.copyOf(e.getValue().values()));
        }
        return new BonusTable<>(Collections.unmodifiableMap(built));
    }

    /**
     * The row covering {@code name} in {@code moment}, or null when nothing covers it (or the name
     * is blank). Case is ignored.
     */
    @Nullable
    public E bestFor(@Nonnull BonusMoment moment, @Nullable String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        List<Row<E>> candidates = rows.get(moment);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String candidate = name.toLowerCase(Locale.ROOT);
        E best = null;
        NameMatchRank bestRank = null;
        for (Row<E> row : candidates) {
            if (!row.pattern().matches(candidate)) {
                continue;
            }
            NameMatchRank rank = NameMatchRank.ofPattern(row.pattern());
            if (rank.isMoreSpecificThan(bestRank)) {
                bestRank = rank;
                best = row.entry();
            }
        }
        return best;
    }

    /** Every folded row: the moments in declaration order, each in fold order. */
    @Nonnull
    public List<E> entries() {
        List<E> out = new ArrayList<>();
        for (List<Row<E>> moment : rows.values()) {
            for (Row<E> row : moment) {
                out.add(row.entry());
            }
        }
        return out;
    }

    /** One moment's rows paired with the patterns they match on, in fold order (the audit's walk). */
    @Nonnull
    public List<Map.Entry<NamePattern, E>> patterned(@Nonnull BonusMoment moment) {
        List<Map.Entry<NamePattern, E>> out = new ArrayList<>();
        List<Row<E>> list = rows.get(moment);
        if (list != null) {
            for (Row<E> row : list) {
                out.add(Map.entry(row.pattern(), row.entry()));
            }
        }
        return out;
    }

    /** How many rows the table holds across every moment. */
    public int size() {
        int total = 0;
        for (List<Row<E>> moment : rows.values()) {
            total += moment.size();
        }
        return total;
    }
}
