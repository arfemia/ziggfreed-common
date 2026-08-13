package com.ziggfreed.common.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.match.NamePattern.Kind;

/**
 * The specificity ladder: band order, the core-length-dominates-anchoring rule inside the partial
 * band, the first-wins-on-tie fold, and the whole thing exercised as a SELECTION (score every
 * candidate, keep the strictly more specific one) because that is how every consumer uses it.
 * Pure decision core, no engine, no balance data.
 */
class NameMatchRankTest {

    private static NameMatchRank rank(String pattern) {
        return NameMatchRank.ofPattern(NamePattern.parse(pattern));
    }

    /** The winning pattern for {@code candidate}, folded exactly the way a consumer folds it. */
    private static String winner(List<String> patterns, String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        NameMatchRank best = null;
        String winner = null;
        for (String pattern : patterns) {
            NamePattern parsed = NamePattern.parse(pattern);
            if (!parsed.matches(lower)) {
                continue;
            }
            NameMatchRank candidateRank = NameMatchRank.ofPattern(parsed);
            if (candidateRank.isMoreSpecificThan(best)) {
                best = candidateRank;
                winner = pattern;
            }
        }
        return winner;
    }

    @Test
    void bandsOrderMostSpecificFirst() {
        NameMatchRank above = NameMatchRank.abovePatterns();
        NameMatchRank exact = rank("world");
        NameMatchRank partial = rank("world*");
        NameMatchRank any = rank("*");

        assertTrue(above.isMoreSpecificThan(exact), "a consumer axis outranks an exact name");
        assertTrue(exact.isMoreSpecificThan(partial), "an exact match outranks a partial");
        assertTrue(partial.isMoreSpecificThan(any), "a partial outranks the bare catch-all");
        assertTrue(any.isMoreSpecificThan(null), "any rank beats no match at all");

        List<NameMatchRank> shuffled = new ArrayList<>(List.of(any, partial, above, exact));
        Collections.sort(shuffled);
        assertEquals(List.of(above, exact, partial, any), shuffled,
                "natural order must be most-specific-first");
    }

    @Test
    void bandsMapToTheDocumentedKinds() {
        assertEquals(NameMatchRank.ABOVE_PATTERNS_BAND, NameMatchRank.abovePatterns().band());
        assertEquals(NameMatchRank.EXACT_BAND, rank("world").band());
        assertEquals(NameMatchRank.PARTIAL_BAND, rank("world*").band());
        assertEquals(NameMatchRank.PARTIAL_BAND, rank("*world").band());
        assertEquals(NameMatchRank.PARTIAL_BAND, rank("*world*").band());
        assertEquals(NameMatchRank.ANY_BAND, rank("*").band());
    }

    @Test
    void longerLiteralCoreBeatsMoreAnchoredKind() {
        // A contains pattern (the LEAST anchored kind) that pins down more characters must win:
        // it is the only form that reaches a decorated name, and it named the thing outright.
        assertTrue(rank("*forgotten_temple*").isMoreSpecificThan(rank("inst*")));
        assertEquals("instance-*", winner(List.of("*kweebec*", "instance-*"), "instance-kweebec_1"),
                "and the same rule the other way round: the longer core wins whatever its kind");
    }

    @Test
    void anchoringBreaksAnEqualCoreLengthTie() {
        NameMatchRank prefix = rank("foo*");
        NameMatchRank suffix = rank("*foo");
        NameMatchRank contains = rank("*foo*");

        assertTrue(prefix.isMoreSpecificThan(suffix));
        assertTrue(suffix.isMoreSpecificThan(contains));
        assertEquals(Kind.PREFIX.ordinal(), prefix.anchorOrdinal());
        assertEquals(Kind.CONTAINS.ordinal(), contains.anchorOrdinal());
    }

    @Test
    void nonPartialBandsNormalizeBothTieBreakers() {
        // Two exact matches are equally specific whatever their length: within a band other than
        // PARTIAL the tie-breakers must not vary, or comparison would order arbitrary pairs.
        assertEquals(rank("a"), rank("a_much_longer_name"));
        assertEquals(0, NameMatchRank.abovePatterns().coreLength());
        assertEquals(0, NameMatchRank.abovePatterns().anchorOrdinal());
    }

    @Test
    void moreSpecificKeepsTheFirstOfTwoEqualRanks() {
        NameMatchRank first = rank("foo*");
        NameMatchRank second = rank("bar*"); // same band, same core length, same kind
        assertEquals(first, second, "the fixture needs two ranks that compare equal");
        assertSame(first, NameMatchRank.moreSpecific(first, second),
                "a tie keeps the earlier-authored match, so authoring order decides what the ladder cannot");
    }

    @Test
    void moreSpecificHandlesNullsOnEitherSide() {
        NameMatchRank exact = rank("world");
        assertSame(exact, NameMatchRank.moreSpecific(null, exact));
        assertSame(exact, NameMatchRank.moreSpecific(exact, null));
        assertNull(NameMatchRank.moreSpecific(null, null));
    }

    // ==================== the ladder as a selection ====================

    @Test
    void exactBeatsPrefixAndTheCatchAll() {
        assertEquals("dungeon_i", winner(List.of("*", "dungeon_i*", "dungeon_i"), "dungeon_i"));
    }

    @Test
    void longestPrefixWins() {
        List<String> patterns = List.of("dungeon_i*", "dungeon_ii*", "dungeon_iii*");
        assertEquals("dungeon_ii*", winner(patterns, "dungeon_ii_xyz42"));
        assertEquals("dungeon_iii*", winner(patterns, "dungeon_iii"));
        // the bare _i name matches only the _i* prefix (the _ii/_iii cores are longer than it)
        assertEquals("dungeon_i*", winner(patterns, "dungeon_i_abc"));
    }

    @Test
    void theCatchAllOnlyWinsWhenNothingElseMatches() {
        assertEquals("*", winner(List.of("dungeon_i*", "*"), "overworld"));
        assertNull(winner(List.of("dungeon_i*"), "overworld"), "nothing matched is a real answer");
    }

    @Test
    void anEqualRankKeepsTheEarlierAuthoredRule() {
        // Two contains patterns with equal-length cores are equally specific, so nothing in the
        // ladder separates them and the first one authored keeps the name.
        assertEquals("*abc*", winner(List.of("*abc*", "*bcd*"), "zabcdz"));
        assertEquals("*bcd*", winner(List.of("*bcd*", "*abc*"), "zabcdz"));
    }
}
