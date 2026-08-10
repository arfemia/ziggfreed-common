package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldNameMatcher.Kind;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;

/**
 * The specificity ladder itself: band order, the core-length-dominates-kind rule inside the
 * partial band, and the first-wins-on-tie fold. Pure decision core, no engine, no balance data.
 */
class MatchRankTest {

    private static MatchRank rank(String pattern) {
        return MatchRank.ofNamePattern(Pattern.parse(pattern));
    }

    @Test
    void bandsOrderMostSpecificFirst() {
        MatchRank gameplayConfig = MatchRank.gameplayConfig();
        MatchRank exact = rank("world");
        MatchRank partial = rank("world*");
        MatchRank any = rank("*");

        assertTrue(gameplayConfig.isMoreSpecificThan(exact), "GameplayConfig outranks an exact name");
        assertTrue(exact.isMoreSpecificThan(partial), "an exact name outranks a partial");
        assertTrue(partial.isMoreSpecificThan(any), "a partial outranks the bare catch-all");
        assertTrue(any.isMoreSpecificThan(null), "any rank beats no match at all");

        List<MatchRank> shuffled = new ArrayList<>(List.of(any, partial, gameplayConfig, exact));
        Collections.sort(shuffled);
        assertEquals(List.of(gameplayConfig, exact, partial, any), shuffled,
                "natural order must be most-specific-first");
    }

    @Test
    void bandsMapToTheDocumentedKinds() {
        assertEquals(MatchRank.GAMEPLAY_CONFIG_BAND, MatchRank.gameplayConfig().band());
        assertEquals(MatchRank.EXACT_NAME_BAND, rank("world").band());
        assertEquals(MatchRank.PARTIAL_BAND, rank("world*").band());
        assertEquals(MatchRank.PARTIAL_BAND, rank("*world").band());
        assertEquals(MatchRank.PARTIAL_BAND, rank("*world*").band());
        assertEquals(MatchRank.ANY_BAND, rank("*").band());
    }

    @Test
    void longerLiteralCoreBeatsMoreAnchoredKind() {
        // A contains pattern (the LEAST anchored kind) that pins down more characters must win:
        // it is the only form that can reach an instance world, and it named the world outright.
        MatchRank precise = rank("*forgotten_temple*");
        MatchRank vague = rank("inst*");
        assertTrue(precise.isMoreSpecificThan(vague),
                "core length dominates anchoring inside the partial band");
    }

    @Test
    void anchoringBreaksAnEqualCoreLengthTie() {
        MatchRank prefix = rank("foo*");
        MatchRank suffix = rank("*foo");
        MatchRank contains = rank("*foo*");

        assertTrue(prefix.isMoreSpecificThan(suffix));
        assertTrue(suffix.isMoreSpecificThan(contains));
        assertEquals(Kind.PREFIX.ordinal(), prefix.kindOrdinal());
        assertEquals(Kind.CONTAINS.ordinal(), contains.kindOrdinal());
    }

    @Test
    void nonPartialBandsNormalizeBothTieBreakers() {
        // Two exact matches are equally specific whatever their length: within a band other than
        // PARTIAL the tie-breakers must not vary, or comparison would order arbitrary pairs.
        assertEquals(rank("a"), rank("a_much_longer_world_name"));
        assertEquals(0, MatchRank.gameplayConfig().coreLength());
        assertEquals(0, MatchRank.gameplayConfig().kindOrdinal());
    }

    @Test
    void moreSpecificKeepsTheFirstOfTwoEqualRanks() {
        MatchRank first = rank("foo*");
        MatchRank second = rank("bar*"); // same band, same core length, same kind
        assertEquals(first, second, "the fixture needs two ranks that compare equal");
        assertSame(first, MatchRank.moreSpecific(first, second),
                "a tie must keep the earlier-authored match, matching WorldNameMatcher.resolve");
    }

    @Test
    void moreSpecificHandlesNullsOnEitherSide() {
        MatchRank exact = rank("world");
        assertSame(exact, MatchRank.moreSpecific(null, exact));
        assertSame(exact, MatchRank.moreSpecific(exact, null));
        assertNull(MatchRank.moreSpecific(null, null));
        assertFalse(exact.isMoreSpecificThan(MatchRank.gameplayConfig()));
    }
}
