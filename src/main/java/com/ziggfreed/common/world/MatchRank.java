package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.world.WorldNameMatcher.Kind;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;

/**
 * How SPECIFICALLY a world selector matched a world - the reason a selector is a named,
 * reusable MATCHER rather than an opaque tag. A tag would carry no intrinsic specificity, so
 * two rules targeting the same world could not be ordered; a rank derived from the pattern that
 * actually matched sorts named and inline rules in ONE ladder, the same ladder a server owner
 * already knows from raw match patterns.
 *
 * <p>The ladder, most specific first:
 * <ol>
 *   <li><b>band 0</b> - {@link #GAMEPLAY_CONFIG_BAND}: an exact {@code GameplayConfig} match.
 *       The authored, uuid-free machine key of a world ("this world IS the Forgotten Temple"),
 *       so it outranks even an exact name: a name can be a random per-instance string, a
 *       gameplay config never is.</li>
 *   <li><b>band 1</b> - {@link #EXACT_NAME_BAND}: the pattern equals the world name.</li>
 *   <li><b>band 2</b> - {@link #PARTIAL_BAND}: prefix / suffix / contains. The LONGER literal
 *       core wins; only on an equal core length does the more anchored kind win
 *       (prefix &gt; suffix &gt; contains).</li>
 *   <li><b>band 3</b> - {@link #ANY_BAND}: the bare {@code *} catch-all.</li>
 * </ol>
 *
 * <p><b>Why core length dominates kind inside band 2.</b> The literal core is how much of the
 * world name the author actually pinned down, and it is the only measure that survives the
 * instance-world naming scheme: a live instance world is
 * {@code instance-Forgotten_Temple-<uuid>}, so the ONLY pattern form that can reach it is
 * {@code *Forgotten_Temple*} (contains - the least anchored kind). Ranking by anchoring first
 * would let a vague but well-anchored {@code inst*} beat a precise
 * {@code *Forgotten_Temple*}, which is backwards: the second author named the world, the first
 * merely guessed at its prefix. Anchoring is therefore only a tie-break, for the genuinely
 * ambiguous case of two patterns pinning down the same NUMBER of characters.
 *
 * <p><b>Natural order is most-specific-first</b>: {@code a.compareTo(b) < 0} means {@code a} is
 * the MORE specific match, so sorting a list of ranks puts the winner at index 0 and
 * {@link java.util.Collections#min} picks it. Comparison is total and deterministic - band
 * ascending, then core length DESCENDING, then kind ordinal ascending. Bands other than
 * {@link #PARTIAL_BAND} normalize both tie-breakers to {@code 0}, so within those bands every
 * rank compares equal (as it should: two exact-name matches are equally specific).
 *
 * <p>Ties never silently reorder authored content: use {@link #moreSpecific} to fold a stream of
 * candidate ranks, which keeps the FIRST of two equally specific matches, matching
 * {@link WorldNameMatcher#resolve}'s own first-wins behaviour.
 *
 * @param band       the ladder band, ascending = more specific (0..3)
 * @param coreLength the matched pattern's literal core length ({@link #PARTIAL_BAND} only, else 0)
 * @param kindOrdinal the matched {@link Kind}'s ordinal, the anchoring tie-break
 *                   ({@link #PARTIAL_BAND} only, else 0)
 */
public record MatchRank(int band, int coreLength, int kindOrdinal) implements Comparable<MatchRank> {

    /** An exact {@code GameplayConfig} match - the most specific thing a selector can say. */
    public static final int GAMEPLAY_CONFIG_BAND = 0;
    /** The pattern equals the world name. */
    public static final int EXACT_NAME_BAND = 1;
    /** A prefix / suffix / contains name pattern, ordered by literal core length. */
    public static final int PARTIAL_BAND = 2;
    /** The bare {@code *} catch-all. */
    public static final int ANY_BAND = 3;

    /** The rank of an exact {@code GameplayConfig} match. */
    @Nonnull
    public static MatchRank gameplayConfig() {
        return new MatchRank(GAMEPLAY_CONFIG_BAND, 0, 0);
    }

    /**
     * The rank of a name pattern that has already been established to MATCH the world (the
     * caller tests the match, this scores it). Every {@link Kind} maps onto a band, so this
     * always ranks.
     */
    @Nonnull
    public static MatchRank ofNamePattern(@Nonnull Pattern pattern) {
        return switch (pattern.kind()) {
            case EXACT -> new MatchRank(EXACT_NAME_BAND, 0, 0);
            case PREFIX, SUFFIX, CONTAINS ->
                    new MatchRank(PARTIAL_BAND, pattern.coreLength(), pattern.kind().ordinal());
            case ALL -> new MatchRank(ANY_BAND, 0, 0);
        };
    }

    /**
     * Fold a candidate into the current best: returns {@code candidate} only when it is
     * STRICTLY more specific, so the first of two equally specific matches wins (the authoring
     * order rule {@link WorldNameMatcher#resolve} already follows). Either side may be null.
     */
    @Nullable
    public static MatchRank moreSpecific(@Nullable MatchRank current, @Nullable MatchRank candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return candidate.compareTo(current) < 0 ? candidate : current;
    }

    /** True when this rank is strictly more specific than {@code other} (null = no match at all). */
    public boolean isMoreSpecificThan(@Nullable MatchRank other) {
        return other == null || compareTo(other) < 0;
    }

    @Override
    public int compareTo(@Nonnull MatchRank o) {
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
        return Integer.compare(kindOrdinal, o.kindOrdinal);
    }
}
