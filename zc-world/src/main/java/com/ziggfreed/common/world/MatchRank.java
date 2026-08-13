package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.match.NameMatchRank;
import com.ziggfreed.common.match.NamePattern;

/**
 * How SPECIFICALLY a world selector matched a world - the reason a selector is a named, reusable
 * MATCHER rather than an opaque tag. A tag would carry no intrinsic specificity, so two rules
 * targeting the same world could not be ordered; a rank derived from the pattern that actually
 * matched sorts named and inline rules in ONE ladder, the same ladder a server owner already knows
 * from raw match patterns.
 *
 * <p>It is the shared {@link NameMatchRank} ladder plus ONE world-specific rung: the
 * {@code GameplayConfig} band above every name pattern. The comparison, the bands and the
 * first-wins-on-tie fold all live in the shared record, so a world rule and any other name-matched
 * rule in the library can never sort differently.
 *
 * <p>The ladder, most specific first:
 * <ol>
 *   <li><b>band 0</b> - {@link #GAMEPLAY_CONFIG_BAND}: an exact {@code GameplayConfig} match. The
 *       authored, uuid-free machine key of a world ("this world IS the Forgotten Temple"), so it
 *       outranks even an exact name: a name can be a random per-instance string, a gameplay config
 *       never is.</li>
 *   <li><b>band 1</b> - {@link #EXACT_NAME_BAND}: the pattern equals the world name.</li>
 *   <li><b>band 2</b> - {@link #PARTIAL_BAND}: prefix / suffix / contains, the LONGER literal core
 *       winning and anchoring only breaking a tie.</li>
 *   <li><b>band 3</b> - {@link #ANY_BAND}: the bare {@code *} catch-all.</li>
 * </ol>
 *
 * <p><b>Natural order is most-specific-first</b>: {@code a.compareTo(b) < 0} means {@code a} is the
 * MORE specific match, so sorting puts the winner at index 0. Use {@link #moreSpecific} to fold
 * candidates, which keeps the FIRST of two equally specific matches - so where the ladder has
 * nothing left to say, authoring order decides, and a server owner can read the winner off the
 * files rather than off map iteration order.
 *
 * @param shared the shared ladder position this world rank is
 */
public record MatchRank(@Nonnull NameMatchRank shared) implements Comparable<MatchRank> {

    /** An exact {@code GameplayConfig} match - the most specific thing a selector can say. */
    public static final int GAMEPLAY_CONFIG_BAND = NameMatchRank.ABOVE_PATTERNS_BAND;
    /** The pattern equals the world name. */
    public static final int EXACT_NAME_BAND = NameMatchRank.EXACT_BAND;
    /** A prefix / suffix / contains name pattern, ordered by literal core length. */
    public static final int PARTIAL_BAND = NameMatchRank.PARTIAL_BAND;
    /** The bare {@code *} catch-all. */
    public static final int ANY_BAND = NameMatchRank.ANY_BAND;

    /** The rank of an exact {@code GameplayConfig} match. */
    @Nonnull
    public static MatchRank gameplayConfig() {
        return new MatchRank(NameMatchRank.abovePatterns());
    }

    /**
     * The rank of a name pattern that has already been established to MATCH the world (the caller
     * tests the match, this scores it). Every pattern kind maps onto a band, so this always ranks.
     */
    @Nonnull
    public static MatchRank ofNamePattern(@Nonnull NamePattern pattern) {
        return new MatchRank(NameMatchRank.ofPattern(pattern));
    }

    /** The ladder band, ascending = more specific (0..3). */
    public int band() {
        return shared.band();
    }

    /** The matched pattern's literal core length ({@link #PARTIAL_BAND} only, else 0). */
    public int coreLength() {
        return shared.coreLength();
    }

    /** The matched pattern kind's ordinal, the anchoring tie-break ({@link #PARTIAL_BAND} only). */
    public int kindOrdinal() {
        return shared.anchorOrdinal();
    }

    /**
     * Fold a candidate into the current best: returns {@code candidate} only when it is STRICTLY
     * more specific, so the first of two equally specific matches wins (the authoring-order rule
     * every selection site in the library follows). Either side may be null.
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
        return shared.compareTo(o.shared);
    }
}
