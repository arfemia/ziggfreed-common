package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.match.NamePattern.Kind;

/**
 * The world-name ladder, end to end: exact &gt; longest literal core (prefix/suffix/contains,
 * tie-break prefix &gt; suffix &gt; contains) &gt; bare {@code *}, case-insensitivity, and the
 * nothing-matched contract.
 *
 * <p>Selection is asserted the way production does it - score each candidate through
 * {@link WorldSelector} and keep the strictly-more-specific {@link MatchRank}, so an equal rank
 * leaves the earlier-authored rule standing. There is exactly one selection path in the library,
 * and this is a test of that path rather than of a second one written to mirror it.
 */
class WorldNameMatcherTest {

    /** One authored rule: a pattern, standing for whatever payload a consumer would hang off it. */
    private record Rule(String pattern) {
    }

    /** The winning pattern for {@code worldName}, or null when nothing matched. */
    private static String winner(List<Rule> rules, String worldName) {
        MatchRank best = null;
        String winner = null;
        for (Rule rule : rules) {
            MatchRank rank = WorldSelector.of(null, new String[]{rule.pattern()}, null, null)
                    .match(worldName, null, WorldNameIndex.EMPTY);
            if (rank != null && rank.isMoreSpecificThan(best)) {
                best = rank;
                winner = rule.pattern();
            }
        }
        return winner;
    }

    private static List<Rule> rules(String... patterns) {
        return List.of(patterns).stream().map(Rule::new).toList();
    }

    @Test
    void exactBeatsPrefixAndWildcard() {
        assertEquals("dungeon_i", winner(rules("*", "dungeon_i*", "dungeon_i"), "dungeon_i"));
    }

    @Test
    void longestPrefixWins() {
        List<Rule> rules = rules("dungeon_i*", "dungeon_ii*", "dungeon_iii*");
        assertEquals("dungeon_ii*", winner(rules, "dungeon_ii_xyz42"));
        assertEquals("dungeon_iii*", winner(rules, "dungeon_iii"));
        // the bare _i world matches only the _i* prefix (the _ii/_iii prefixes are longer than it)
        assertEquals("dungeon_i*", winner(rules, "dungeon_i_abc"));
    }

    @Test
    void wildcardIsTheCatchAll() {
        assertEquals("*", winner(rules("dungeon_i*", "*"), "overworld"));
    }

    @Test
    void caseInsensitive() {
        assertEquals("KweebecNightmare_*", winner(rules("KweebecNightmare_*"), "kweebecnightmare_run7"));
    }

    @Test
    void noMatchAndBlankReturnNull() {
        assertNull(winner(rules("dungeon_i*"), "overworld"));
        assertNull(winner(rules("dungeon_i*"), null));
        assertNull(winner(rules("dungeon_i*"), ""));
        assertNull(winner(List.of(), "dungeon_i"));
    }

    @Test
    void suffixMatchesEnding() {
        assertEquals("*_boss", winner(rules("*_boss"), "dungeon_i_boss"));
        assertNull(winner(rules("*_boss"), "dungeon_i_boss_arena")); // must END with _boss
    }

    @Test
    void containsMatchesAnywhere() {
        List<Rule> rules = rules("*KweebecNightmare_*");
        // the real instance world: a leading instance- prefix AND a random suffix
        assertEquals("*KweebecNightmare_*", winner(rules, "instance-KweebecNightmare_Chase_Dread-9f3a"));
        assertEquals("*KweebecNightmare_*", winner(rules, "KweebecNightmare_run7"));
        assertNull(winner(rules, "overworld"));
    }

    @Test
    void containsCatchesInstancePrefixWhereBareTrailingPrefixCannot() {
        // A trailing-* PREFIX cannot catch an instance-prefixed world; a contains pattern can.
        String world = "instance-KweebecNightmare_Chase-9f3a";
        assertNull(winner(rules("KweebecNightmare_*"), world));
        assertEquals("*KweebecNightmare_*", winner(rules("*KweebecNightmare_*"), world));
    }

    @Test
    void tieBreakPrefersMoreAnchoredKindOnEqualCore() {
        // All three cores are "abc" (length 3); the more anchored kind wins: prefix > suffix > contains.
        assertEquals("abc*", winner(rules("*abc*", "*abc", "abc*"), "abcabc"));
        // Drop the prefix: suffix beats contains.
        assertEquals("*abc", winner(rules("*abc*", "*abc"), "abcabc"));
    }

    @Test
    void longestCoreWinsAcrossKinds() {
        // prefix core "instance-" (len 9) beats contains core "kweebec" (len 7) for a world both match.
        assertEquals("instance-*", winner(rules("*kweebec*", "instance-*"), "instance-kweebec_1"));
    }

    @Test
    void anEqualRankKeepsTheEarlierAuthoredRule() {
        // Two contains patterns with equal-length cores are equally specific, so nothing in the
        // ladder separates them and the first one authored keeps the world.
        assertEquals("*abc*", winner(rules("*abc*", "*bcd*"), "zabcdz"));
        assertEquals("*bcd*", winner(rules("*bcd*", "*abc*"), "zabcdz"));
    }

    @Test
    void patternExposesTheSharedKindAndCore() {
        // The world flavour speaks the SHARED grammar vocabulary, so a world rule and any other
        // name-matched rule in the library describe the same pattern shape in the same words.
        assertEquals(Kind.CONTAINS, WorldNameMatcher.Pattern.parse("*Foo*").kind());
        assertEquals("foo", WorldNameMatcher.Pattern.parse("*Foo*").core());
        assertEquals(Kind.EXACT, WorldNameMatcher.Pattern.parse("default").kind());
        assertEquals("default", WorldNameMatcher.Pattern.parse("default").core());
        assertEquals(Kind.ALL, WorldNameMatcher.Pattern.parse("*").kind());
        assertEquals("", WorldNameMatcher.Pattern.parse("*").core());
    }
}
