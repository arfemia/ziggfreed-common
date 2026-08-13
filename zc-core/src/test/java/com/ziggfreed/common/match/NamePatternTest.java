package com.ziggfreed.common.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.match.NamePattern.Kind;

/**
 * The shared name-pattern grammar: which of the five kinds a string parses as, what its literal
 * core is, and what it matches. Pure decision core, no engine, no balance data.
 *
 * <p>Every consumer of the grammar (a world selector, a loot trigger key) rests on exactly these
 * answers, so this file is where the grammar is pinned and a consumer's own tests cover only its
 * own selection.
 */
class NamePatternTest {

    private static NamePattern parse(String pattern) {
        return NamePattern.parse(pattern);
    }

    @Test
    void theFiveKindsParseAsDocumented() {
        assertEquals(Kind.EXACT, parse("default").kind());
        assertEquals(Kind.PREFIX, parse("Dungeon_*").kind());
        assertEquals(Kind.SUFFIX, parse("*_boss").kind());
        assertEquals(Kind.CONTAINS, parse("*Forgotten_Temple*").kind());
        assertEquals(Kind.ALL, parse("*").kind());
        assertEquals(Kind.ALL, parse("**").kind(), "a doubled wildcard is still the catch-all");
    }

    @Test
    void theCoreIsThePatternMinusItsWildcardsLowerCased() {
        assertEquals("forgotten_temple", parse("*Forgotten_Temple*").core());
        assertEquals("dungeon_", parse("Dungeon_*").core());
        assertEquals("_boss", parse("*_BOSS").core());
        assertEquals("default", parse("  Default  ").core(), "an authored pattern is trimmed");
        assertEquals("", parse("*").core());
        assertEquals(16, parse("*Forgotten_Temple*").coreLength());
    }

    @Test
    void theRawPatternIsKeptForDiagnostics() {
        assertEquals("*Forgotten_Temple*", parse("*Forgotten_Temple*").raw,
                "a validator has to be able to quote what the author actually wrote");
    }

    @Test
    void exactMatchesOnlyTheWholeName() {
        NamePattern exact = parse("default");
        assertTrue(exact.matches("default"));
        assertTrue(exact.matchesExact("default"));
        assertFalse(exact.matches("default_2"));
        assertFalse(exact.matchesPartial("default"), "an exact pattern is never a partial match");
    }

    @Test
    void prefixSuffixAndContainsMatchTheirOwnEnds() {
        assertTrue(parse("dungeon_*").matches("dungeon_01"));
        assertFalse(parse("dungeon_*").matches("instance-dungeon_01"));

        assertTrue(parse("*_boss").matches("dungeon_i_boss"));
        assertFalse(parse("*_boss").matches("dungeon_i_boss_arena"));

        assertTrue(parse("*dungeon_01*").matches("instance-dungeon_01-9f3a"));
        assertTrue(parse("*dungeon_01*").matches("dungeon_01"));
        assertFalse(parse("*dungeon_01*").matches("overworld"));
    }

    @Test
    void containsIsTheOnlyFormThatReachesADecoratedName() {
        // The shape an instance world spawns with: an engine prefix AND a random suffix.
        String decorated = "instance-kweebecnightmare_chase-9f3a";
        assertFalse(parse("kweebecnightmare_*").matches(decorated));
        assertFalse(parse("*kweebecnightmare").matches(decorated));
        assertTrue(parse("*kweebecnightmare*").matches(decorated));
    }

    @Test
    void theCatchAllMatchesAnythingAndSaysSo() {
        NamePattern all = parse("*");
        assertTrue(all.isDefaultRule());
        assertTrue(all.matches("anything_at_all"));
        assertTrue(all.matches(""));
        assertFalse(parse("dungeon_*").isDefaultRule());
    }

    @Test
    void matchingIsCaseInsensitiveThroughTheLowerCasedCore() {
        // The caller lower-cases the candidate once and tests it against many patterns; the
        // pattern's own core is lower-cased at parse time, so authored capitals never matter.
        assertTrue(parse("KweebecNightmare_*").matches("kweebecnightmare_run7"));
        assertTrue(parse("*Forgotten_Temple*").matches("instance-forgotten_temple-1"));
    }
}
