package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorldNameMatcher}: exact &gt; longest literal core
 * (prefix/suffix/contains, tie-break prefix &gt; suffix &gt; contains) &gt; bare {@code *}
 * precedence, case-insensitivity, and the null-on-no-match contract.
 */
class WorldNameMatcherTest {

    private static WorldNameMatcher.Entry<String> e(String pattern) {
        return new WorldNameMatcher.Entry<>(pattern, pattern);
    }

    @Test
    void exactBeatsPrefixAndWildcard() {
        List<WorldNameMatcher.Entry<String>> entries =
                List.of(e("*"), e("dungeon_i*"), e("dungeon_i"));
        assertEquals("dungeon_i", WorldNameMatcher.resolve(entries, "dungeon_i"));
    }

    @Test
    void longestPrefixWins() {
        List<WorldNameMatcher.Entry<String>> entries =
                List.of(e("dungeon_i*"), e("dungeon_ii*"), e("dungeon_iii*"));
        assertEquals("dungeon_ii*", WorldNameMatcher.resolve(entries, "dungeon_ii_xyz42"));
        assertEquals("dungeon_iii*", WorldNameMatcher.resolve(entries, "dungeon_iii"));
        // the bare _i world matches only the _i* prefix (the _ii/_iii prefixes are longer than it)
        assertEquals("dungeon_i*", WorldNameMatcher.resolve(entries, "dungeon_i_abc"));
    }

    @Test
    void wildcardIsTheCatchAll() {
        List<WorldNameMatcher.Entry<String>> entries = List.of(e("dungeon_i*"), e("*"));
        assertEquals("*", WorldNameMatcher.resolve(entries, "overworld"));
    }

    @Test
    void caseInsensitive() {
        List<WorldNameMatcher.Entry<String>> entries = List.of(e("KweebecNightmare_*"));
        assertEquals("KweebecNightmare_*", WorldNameMatcher.resolve(entries, "kweebecnightmare_run7"));
    }

    @Test
    void noMatchAndBlankReturnNull() {
        List<WorldNameMatcher.Entry<String>> entries = List.of(e("dungeon_i*"));
        assertNull(WorldNameMatcher.resolve(entries, "overworld"));
        assertNull(WorldNameMatcher.resolve(entries, null));
        assertNull(WorldNameMatcher.resolve(entries, ""));
        assertNull(WorldNameMatcher.resolve(List.of(), "dungeon_i"));
    }

    @Test
    void suffixMatchesEnding() {
        List<WorldNameMatcher.Entry<String>> entries = List.of(e("*_boss"));
        assertEquals("*_boss", WorldNameMatcher.resolve(entries, "dungeon_i_boss"));
        assertNull(WorldNameMatcher.resolve(entries, "dungeon_i_boss_arena")); // must END with _boss
    }

    @Test
    void containsMatchesAnywhere() {
        List<WorldNameMatcher.Entry<String>> entries = List.of(e("*KweebecNightmare_*"));
        // the real instance world: a leading instance- prefix AND a random suffix
        assertEquals("*KweebecNightmare_*",
                WorldNameMatcher.resolve(entries, "instance-KweebecNightmare_Chase_Dread-9f3a"));
        assertEquals("*KweebecNightmare_*", WorldNameMatcher.resolve(entries, "KweebecNightmare_run7"));
        assertNull(WorldNameMatcher.resolve(entries, "overworld"));
    }

    @Test
    void containsCatchesInstancePrefixWhereBareTrailingPrefixCannot() {
        // A trailing-* PREFIX cannot catch an instance-prefixed world; a contains pattern can.
        String world = "instance-KweebecNightmare_Chase-9f3a";
        assertNull(WorldNameMatcher.resolve(List.of(e("KweebecNightmare_*")), world));
        assertEquals("*KweebecNightmare_*",
                WorldNameMatcher.resolve(List.of(e("*KweebecNightmare_*")), world));
    }

    @Test
    void tieBreakPrefersMoreAnchoredKindOnEqualCore() {
        // All three cores are "abc" (length 3); the more anchored kind wins: prefix > suffix > contains.
        List<WorldNameMatcher.Entry<String>> all = List.of(e("*abc*"), e("*abc"), e("abc*"));
        assertEquals("abc*", WorldNameMatcher.resolve(all, "abcabc")); // prefix beats suffix + contains
        // Drop the prefix: suffix beats contains.
        assertEquals("*abc", WorldNameMatcher.resolve(List.of(e("*abc*"), e("*abc")), "abcabc"));
    }

    @Test
    void longestCoreWinsAcrossKinds() {
        // prefix core "instance-" (len 9) beats contains core "kweebec" (len 7) for a world both match.
        List<WorldNameMatcher.Entry<String>> entries = List.of(e("*kweebec*"), e("instance-*"));
        assertEquals("instance-*", WorldNameMatcher.resolve(entries, "instance-kweebec_1"));
    }
}
