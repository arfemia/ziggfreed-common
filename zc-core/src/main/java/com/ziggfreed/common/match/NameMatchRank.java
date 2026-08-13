package com.ziggfreed.common.match;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.match.NamePattern.Kind;

/**
 * How SPECIFICALLY a {@link NamePattern} matched - the number that lets two rules pointing at the
 * same thing be ordered instead of resolved by whichever the runtime happened to read first.
 *
 * <p>The ladder, most specific first:
 * <ol>
 *   <li><b>band 0</b> - {@link #ABOVE_PATTERNS_BAND}: reserved for a consumer axis that is stronger
 *       than any name pattern (an exact machine key, an authored id). Nothing here produces it;
 *       {@link #abovePatterns()} is how a consumer says its own axis hit.</li>
 *   <li><b>band 1</b> - {@link #EXACT_BAND}: the pattern equals the name.</li>
 *   <li><b>band 2</b> - {@link #PARTIAL_BAND}: prefix / suffix / contains. The LONGER literal core
 *       wins; only on an equal core length does the more anchored kind win
 *       (prefix &gt; suffix &gt; contains).</li>
 *   <li><b>band 3</b> - {@link #ANY_BAND}: the bare {@code *} catch-all.</li>
 * </ol>
 *
 * <p><b>Why core length dominates kind inside band 2.</b> The literal core is how much of the name
 * the author actually pinned down, and it is the only measure that survives a name carrying engine
 * decoration at both ends: a live instance world is {@code instance-Forgotten_Temple-<uuid>}, so
 * the only form that reaches it is {@code *Forgotten_Temple*} (contains, the least anchored kind).
 * Ranking by anchoring first would let a vague but well-anchored {@code inst*} beat a precise
 * {@code *Forgotten_Temple*}, which is backwards: the second author named the thing, the first
 * merely guessed at its prefix. Anchoring is therefore only a tie-break, for the genuinely
 * ambiguous case of two patterns pinning down the same NUMBER of characters.
 *
 * <p><b>Natural order is most-specific-first</b>: {@code a.compareTo(b) < 0} means {@code a} is the
 * MORE specific match, so sorting a list of ranks puts the winner at index 0 and
 * {@link java.util.Collections#min} picks it. Comparison is total and deterministic - band
 * ascending, then core length DESCENDING, then anchor ordinal ascending. Bands other than
 * {@link #PARTIAL_BAND} normalize both tie-breakers to {@code 0}, so within those bands every rank
 * compares equal (as it should: two exact matches are equally specific).
 *
 * <p>Ties never silently reorder authored content: use {@link #moreSpecific} to fold a stream of
 * candidate ranks, which keeps the FIRST of two equally specific matches - so where the ladder has
 * nothing left to say, authoring order decides, and a server owner can read the winner off the
 * files rather than off map iteration order.
 *
 * @param band          the ladder band, ascending = more specific (0..3)
 * @param coreLength    the matched pattern's literal core length ({@link #PARTIAL_BAND} only, else 0)
 * @param anchorOrdinal the matched {@link Kind}'s ordinal, the anchoring tie-break
 *                      ({@link #PARTIAL_BAND} only, else 0)
 */
public record NameMatchRank(int band, int coreLength, int anchorOrdinal)
        implements Comparable<NameMatchRank> {

    /** A consumer axis stronger than any name pattern (an exact machine key, an authored id). */
    public static final int ABOVE_PATTERNS_BAND = 0;
    /** The pattern equals the name. */
    public static final int EXACT_BAND = 1;
    /** A prefix / suffix / contains pattern, ordered by literal core length. */
    public static final int PARTIAL_BAND = 2;
    /** The bare {@code *} catch-all. */
    public static final int ANY_BAND = 3;

    /**
     * The rank of a consumer's OWN axis, above every name pattern. Use it for an identifier that
     * cannot be a coincidence (a machine key, an id an author typed on purpose), never as a
     * shortcut for "I want this one to win".
     */
    @Nonnull
    public static NameMatchRank abovePatterns() {
        return new NameMatchRank(ABOVE_PATTERNS_BAND, 0, 0);
    }

    /**
     * The rank of a pattern that has already been established to MATCH (the caller tests the match,
     * this scores it). Every {@link Kind} maps onto a band, so this always ranks.
     */
    @Nonnull
    public static NameMatchRank ofPattern(@Nonnull NamePattern pattern) {
        return switch (pattern.kind()) {
            case EXACT -> new NameMatchRank(EXACT_BAND, 0, 0);
            case PREFIX, SUFFIX, CONTAINS ->
                    new NameMatchRank(PARTIAL_BAND, pattern.coreLength(), pattern.kind().ordinal());
            case ALL -> new NameMatchRank(ANY_BAND, 0, 0);
        };
    }

    /**
     * Fold a candidate into the current best: returns {@code candidate} only when it is STRICTLY
     * more specific, so the first of two equally specific matches wins (the authoring-order rule
     * every selection site in the library follows). Either side may be null.
     */
    @Nullable
    public static NameMatchRank moreSpecific(@Nullable NameMatchRank current,
            @Nullable NameMatchRank candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return candidate.compareTo(current) < 0 ? candidate : current;
    }

    /** True when this rank is strictly more specific than {@code other} (null = no match at all). */
    public boolean isMoreSpecificThan(@Nullable NameMatchRank other) {
        return other == null || compareTo(other) < 0;
    }

    @Override
    public int compareTo(@Nonnull NameMatchRank o) {
        int byBand = Integer.compare(band, o.band);
        if (byBand != 0) {
            return byBand;
        }
        // Longer literal core = more specific, so the comparison is REVERSED here.
        int byCore = Integer.compare(o.coreLength, coreLength);
        if (byCore != 0) {
            return byCore;
        }
        // Lower Kind ordinal = more anchored (PREFIX < SUFFIX < CONTAINS).
        return Integer.compare(anchorOrdinal, o.anchorOrdinal);
    }
}
