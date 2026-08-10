package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldNameMatcher.Entry;

/**
 * <b>The load-bearing test of the whole design.</b> The claim is that precedence SURVIVES the move
 * from a hand-rolled per-consumer matcher to a ranked selector: a rule expressed as a
 * {@link WorldSelector} must pick the same winner as {@link WorldNameMatcher#resolve}, for every
 * {@link WorldNameMatcher.Kind} and every mix of them, including the first-wins-on-tie rule.
 *
 * <p>{@link WorldNameMatcher} is the in-repo reference ladder here (its javadoc records that it is
 * feature-matched to the MMO's own {@code WorldRulesMatcher} - both pre-parse a pattern into a kind
 * plus a literal core and resolve exact &gt; longest core &gt; anchoring &gt; bare {@code *} with
 * the same first-wins tie rule). Common cannot import the MMO's class, so this asserts against the
 * shared shape instead; a divergence in either implementation shows up here.
 *
 * <p>The selector side deliberately re-implements only the SELECTION (fold candidates, keep the
 * strictly better), never the scoring: the ranks come from production code.
 */
class WorldRankParityTest {

    /** One authored rule: a pattern plus the payload it would carry. */
    private record Rule(String pattern, String payload) {
    }

    /**
     * Pick the winning payload by {@link MatchRank}, exactly as a consumer would: score each rule
     * through {@link WorldSelector} and keep the strictly-more-specific one, so an equal rank
     * leaves the earlier-authored rule standing.
     */
    private static String rankWinner(List<Rule> rules, String worldName) {
        MatchRank best = null;
        String winner = null;
        for (Rule rule : rules) {
            MatchRank rank = WorldSelector.of(null, new String[]{rule.pattern()}, null, null)
                    .match(worldName, null, WorldNameIndex.EMPTY);
            if (rank != null && rank.isMoreSpecificThan(best)) {
                best = rank;
                winner = rule.payload();
            }
        }
        return winner;
    }

    /** The reference ladder's own winner for the same rules. */
    private static String ladderWinner(List<Rule> rules, String worldName) {
        List<Entry<String>> entries = new ArrayList<>();
        for (Rule rule : rules) {
            entries.add(new Entry<>(rule.pattern(), rule.payload()));
        }
        return WorldNameMatcher.resolve(entries, worldName);
    }

    private static void assertSameWinner(List<Rule> rules, String worldName) {
        assertEquals(ladderWinner(rules, worldName), rankWinner(rules, worldName),
                () -> "rank selection diverged from the reference ladder for world '" + worldName
                        + "' over rules " + rules);
    }

    // ==================== Per-kind parity ====================

    @Test
    void exactBeatsEveryOtherKind() {
        List<Rule> rules = List.of(new Rule("dungeon_i", "exact"), new Rule("dungeon_i*", "prefix"),
                new Rule("*dungeon_i*", "contains"), new Rule("*", "all"));
        assertSameWinner(rules, "dungeon_i");
        assertEquals("exact", rankWinner(rules, "dungeon_i"));
    }

    @Test
    void longerCoreBeatsShorterCore() {
        List<Rule> rules = List.of(new Rule("dungeon_*", "short"), new Rule("dungeon_ii*", "long"));
        assertSameWinner(rules, "dungeon_ii");
        assertEquals("long", rankWinner(rules, "dungeon_ii"));
    }

    @Test
    void anchoringBreaksAnEqualCoreTie() {
        List<Rule> rules = List.of(new Rule("*foo*", "contains"), new Rule("*foo", "suffix"),
                new Rule("foo*", "prefix"));
        assertSameWinner(rules, "foo");
        assertEquals("prefix", rankWinner(rules, "foo"));
    }

    @Test
    void bareStarIsTheLastResort() {
        List<Rule> rules = List.of(new Rule("*", "all"), new Rule("*temple*", "contains"));
        assertSameWinner(rules, "arena_01");
        assertEquals("all", rankWinner(rules, "arena_01"));
        assertSameWinner(rules, "instance-Forgotten_Temple-8f2c1a");
        assertEquals("contains", rankWinner(rules, "instance-Forgotten_Temple-8f2c1a"));
    }

    @Test
    void nothingMatchingYieldsNoWinnerOnBothSides() {
        List<Rule> rules = List.of(new Rule("arena*", "arena"), new Rule("*_pvp", "pvp"));
        assertNull(ladderWinner(rules, "default"));
        assertNull(rankWinner(rules, "default"));
    }

    @Test
    void anEqualRankKeepsTheEarlierAuthoredRule() {
        // Two CONTAINS patterns with the same core length, both matching "foobar": nothing on the
        // ladder separates them, so authoring order decides and must not flip.
        List<Rule> rules = List.of(new Rule("*oo*", "first"), new Rule("*ob*", "second"));
        assertSameWinner(rules, "foobar");
        assertEquals("first", rankWinner(rules, "foobar"));

        List<Rule> reversed = List.of(new Rule("*ob*", "second"), new Rule("*oo*", "first"));
        assertSameWinner(reversed, "foobar");
        assertEquals("second", rankWinner(reversed, "foobar"));
    }

    // ==================== Exhaustive parity over every subset ====================

    @Test
    void everySubsetOfAMixedRuleSetAgreesOnEveryWorld() {
        List<Rule> pool = List.of(
                new Rule("default", "exact_default"),
                new Rule("dungeon_i*", "prefix_short"),
                new Rule("dungeon_ii*", "prefix_long"),
                new Rule("*_pvp", "suffix"),
                new Rule("*Forgotten_Temple*", "contains_temple"),
                new Rule("*", "catch_all"));
        List<String> worlds = List.of(
                "default",
                "dungeon_i",
                "dungeon_ii",
                "dungeon_iii",
                "arena_pvp",
                "instance-Forgotten_Temple-8f2c1a",
                "instance-KweebecNightmare_Chase-77aa",
                "");

        for (int mask = 0; mask < (1 << pool.size()); mask++) {
            List<Rule> subset = new ArrayList<>();
            for (int i = 0; i < pool.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(pool.get(i));
                }
            }
            for (String world : worlds) {
                assertSameWinner(subset, world);
            }
        }
    }
}
