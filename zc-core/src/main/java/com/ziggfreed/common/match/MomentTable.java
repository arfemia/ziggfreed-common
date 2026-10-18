package com.ziggfreed.common.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Which rows cover a produced moment: the fold and the most-specific match every moment-keyed table shares (a
 * track's sources, the bonus rows), over {@link When}'s three leaves. Immutable; build a new one per fold.
 *
 * <p><b>The fold.</b> Rows group by kind id, and within a kind by their fold key (the authored {@code Match} and
 * {@code Qualifier}, lower-cased), so two rows claiming one pattern in one kind are ONE answer: the later row in
 * fold order wins it and takes the earlier one's place.
 *
 * <p><b>The match.</b> A row covers a moment when its {@code Match} matches the target and its {@code Qualifier},
 * when it names one, matches the moment's qualifier. Covering rows rank by the shared ladder
 * ({@link NameMatchRank}): the {@code Match} first (exact, then the longest literal core, then {@code *}), then
 * the {@code Qualifier} (a row naming one before a row naming none, then the same ladder), and two equally
 * specific rows keep the one folded first. {@link #bestFor} answers the winner; {@link #matching} the whole
 * ladder, most specific first, for a reader that walks down it (a soft gate paying the next row when the first
 * is out of reach).
 *
 * <p><b>The memo.</b> A table is asked the same few hundred targets over and over (every block a player breaks),
 * so each kind keeps its answer per target and qualifier as asked, a hit allocating nothing; a table never changes
 * once folded, so the memo never goes stale. It stops growing at {@link #MEMO_LIMIT} targets a kind (and as many
 * qualifiers a target) and answers the rest by walking.
 *
 * @param <E> the table's own row type
 */
public final class MomentTable<E> {

    /** The most (target, qualifier) answers one kind keeps. */
    public static final int MEMO_LIMIT = 8192;

    /** One row as authored for the fold: its kind, its parsed patterns, the key rows collapse on, the row. */
    public record Row<R>(@Nonnull String kindId, @Nonnull NamePattern match, @Nullable NamePattern qualifier,
            @Nonnull String foldKey, @Nonnull R entry) {

        /**
         * The row {@code when} names for {@code entry}; null when it names no kind. A blank {@code Match} covers
         * every target, a blank {@code Qualifier} asks nothing.
         */
        @Nullable
        public static <R> Row<R> of(@Nonnull When when, @Nonnull R entry) {
            return of(when.kindId(), when.match(), when.qualifier(), entry);
        }

        /** {@link #of(When, Object)} with the leaves given as written; null when {@code kindId} is blank. */
        @Nullable
        public static <R> Row<R> of(@Nullable String kindId, @Nullable String match, @Nullable String qualifier,
                @Nonnull R entry) {
            String kind = When.normalizeKind(kindId);
            if (kind == null) {
                return null;
            }
            String authored = match == null ? "" : match.trim();
            String asked = qualifier == null ? "" : qualifier.trim();
            return new Row<>(kind, NamePattern.parse(authored.isEmpty() ? "*" : authored),
                    asked.isEmpty() ? null : NamePattern.parse(asked),
                    authored.toLowerCase(Locale.ROOT) + '|' + asked.toLowerCase(Locale.ROOT), entry);
        }
    }

    /** The empty table: covers nothing. */
    private static final MomentTable<?> EMPTY = new MomentTable<>(Map.of());

    /** Per kind id, its rows in fold order. */
    @Nonnull
    private final Map<String, List<Row<E>>> rows;

    /** Per kind id, the ladder already worked out per target, then per qualifier ({@code ""} for none). */
    @Nonnull
    private final Map<String, Map<String, Map<String, List<E>>>> memo = new ConcurrentHashMap<>();

    private MomentTable(@Nonnull Map<String, List<Row<E>>> rows) {
        this.rows = rows;
    }

    /** The table that covers nothing. */
    @Nonnull
    @SuppressWarnings("unchecked")
    public static <E> MomentTable<E> empty() {
        return (MomentTable<E>) EMPTY;
    }

    /** The table {@code inFoldOrder} folds to (a null row left out). */
    @Nonnull
    public static <E> MomentTable<E> fold(@Nonnull List<Row<E>> inFoldOrder) {
        Map<String, Map<String, Row<E>>> folded = new LinkedHashMap<>();
        for (Row<E> row : inFoldOrder) {
            if (row != null) {
                folded.computeIfAbsent(row.kindId(), kind -> new LinkedHashMap<>()).put(row.foldKey(), row);
            }
        }
        if (folded.isEmpty()) {
            return empty();
        }
        Map<String, List<Row<E>>> built = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Row<E>>> e : folded.entrySet()) {
            built.put(e.getKey(), List.copyOf(e.getValue().values()));
        }
        return new MomentTable<>(Collections.unmodifiableMap(built));
    }

    /** Does any row answer for {@code kindId}? */
    public boolean hasKind(@Nullable String kindId) {
        String kind = When.normalizeKind(kindId);
        return kind != null && rows.containsKey(kind);
    }

    /** The kind ids some row answers for, in the order they were first folded. */
    @Nonnull
    public Set<String> kinds() {
        return rows.keySet();
    }

    /**
     * The row covering {@code target} (with {@code qualifier}) in {@code kindId} that is most specific, or null
     * when none does or the target is blank. Case is ignored.
     */
    @Nullable
    public E bestFor(@Nullable String kindId, @Nullable String target, @Nullable String qualifier) {
        List<E> ladder = matching(kindId, target, qualifier);
        return ladder.isEmpty() ? null : ladder.get(0);
    }

    /**
     * Every row covering {@code target} (with {@code qualifier}) in {@code kindId}, most specific first; empty when
     * none does or the target is blank. Case is ignored; the answer is kept per (target, qualifier).
     */
    @Nonnull
    public List<E> matching(@Nullable String kindId, @Nullable String target, @Nullable String qualifier) {
        if (target == null || target.isEmpty() || kindId == null) {
            return List.of();
        }
        List<Row<E>> candidates = rows.get(kindId);
        if (candidates == null) {
            if (canonical(kindId)) {
                // Spelt as a producer fires a kind, so no other spelling of it is in the table either.
                return List.of();
            }
            String kind = When.normalizeKind(kindId);
            candidates = kind == null ? null : rows.get(kind);
            if (candidates == null) {
                return List.of();
            }
            kindId = kind;
        }
        // Kept by the target and qualifier as asked (no case fold on a hit, so a hit allocates nothing); a
        // spelling in another case is one more entry with the same answer.
        String byQualifier = qualifier == null ? "" : qualifier;
        Map<String, Map<String, List<E>>> kept = memo.computeIfAbsent(kindId, kind -> new ConcurrentHashMap<>());
        Map<String, List<E>> forTarget = kept.get(target);
        List<E> held = forTarget == null ? null : forTarget.get(byQualifier);
        if (held != null) {
            return held;
        }
        List<E> ladder = ladder(candidates, target.toLowerCase(Locale.ROOT), byQualifier.toLowerCase(Locale.ROOT));
        if (forTarget == null && kept.size() < MEMO_LIMIT) {
            forTarget = kept.computeIfAbsent(target, t -> new ConcurrentHashMap<>());
        }
        if (forTarget != null && forTarget.size() < MEMO_LIMIT) {
            forTarget.put(byQualifier, ladder);
        }
        return ladder;
    }

    /** One kind's rows in fold order, paired with what they match on (an audit's walk). */
    @Nonnull
    public List<Row<E>> rows(@Nullable String kindId) {
        String kind = When.normalizeKind(kindId);
        List<Row<E>> list = kind == null ? null : rows.get(kind);
        return list == null ? List.of() : list;
    }

    /** Every folded row's entry: the kinds in fold order, each in fold order. */
    @Nonnull
    public List<E> entries() {
        List<E> out = new ArrayList<>();
        for (List<Row<E>> kind : rows.values()) {
            for (Row<E> row : kind) {
                out.add(row.entry());
            }
        }
        return out;
    }

    /** How many rows the table holds across every kind. */
    public int size() {
        int total = 0;
        for (List<Row<E>> kind : rows.values()) {
            total += kind.size();
        }
        return total;
    }

    /**
     * Is {@code kindId} spelt as a producer fires a kind (no lower-case letter, no space)? A table keyed by
     * {@link When#normalizeKind} then holds it under that spelling or not at all, so a miss needs no second
     * lookup and allocates nothing (an upper-case first spelling such as {@code KILL_MOB} is the one exception,
     * which no producer fires). Checked without allocating.
     */
    public static boolean canonical(@Nonnull String kindId) {
        for (int i = 0; i < kindId.length(); i++) {
            char c = kindId.charAt(i);
            if (Character.isLowerCase(c) || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    /** The covering rows of {@code candidates}, most specific first, ties in fold order (the sort is stable). */
    @Nonnull
    private static <E> List<E> ladder(@Nonnull List<Row<E>> candidates, @Nonnull String name,
            @Nonnull String asked) {
        List<Row<E>> covering = new ArrayList<>(2);
        for (Row<E> row : candidates) {
            if (row.match().matches(name) && (row.qualifier() == null
                    || (!asked.isEmpty() && row.qualifier().matches(asked)))) {
                covering.add(row);
            }
        }
        if (covering.isEmpty()) {
            return List.of();
        }
        if (covering.size() > 1) {
            covering.sort(SPECIFICITY);
        }
        List<E> out = new ArrayList<>(covering.size());
        for (Row<E> row : covering) {
            out.add(row.entry());
        }
        return List.copyOf(out);
    }

    /** Most specific first: the Match's rank, then a Qualifier named before none, then the Qualifier's rank. */
    private static final Comparator<Row<?>> SPECIFICITY = (a, b) -> {
        int byMatch = NameMatchRank.ofPattern(a.match()).compareTo(NameMatchRank.ofPattern(b.match()));
        if (byMatch != 0) {
            return byMatch;
        }
        if (a.qualifier() == null || b.qualifier() == null) {
            return a.qualifier() == null ? (b.qualifier() == null ? 0 : 1) : -1;
        }
        return NameMatchRank.ofPattern(a.qualifier()).compareTo(NameMatchRank.ofPattern(b.qualifier()));
    };
}
