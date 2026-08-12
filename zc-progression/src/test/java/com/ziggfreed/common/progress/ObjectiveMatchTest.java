package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The behaviour table for both match dialects. Every row here is a point where STRICT and LENIENT
 * disagree, or a boundary a future "simplification" would quietly change, so this is the test that
 * has to fail before the two can ever be merged.
 */
class ObjectiveMatchTest {

    @Nested
    class StrictTargets {

        @Test
        void exactIsCaseSensitive() {
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "Oak_Log", MatchMode.EXACT, "Oak_Log"));
            assertFalse(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "Oak_Log", MatchMode.EXACT, "oak_log"));
        }

        @Test
        void prefixAndContainsAreCaseSensitive() {
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "Oak", MatchMode.PREFIX, "Oak_Log"));
            assertFalse(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "oak", MatchMode.PREFIX, "Oak_Log"));
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "k_L", MatchMode.CONTAINS, "Oak_Log"));
            assertFalse(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "K_l", MatchMode.CONTAINS, "Oak_Log"));
        }

        @Test
        void emptyTargetUnderExactMatchesOnlyAnEmptyIdentifier() {
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "", MatchMode.EXACT, ""));
            assertFalse(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "", MatchMode.EXACT, "Oak_Log"));
        }

        @Test
        void emptyTargetUnderPrefixOrContainsMatchesAnything() {
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "", MatchMode.PREFIX, "Oak_Log"));
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.STRICT, "", MatchMode.CONTAINS, "Oak_Log"));
        }
    }

    @Nested
    class LenientTargets {

        @Test
        void everyModeIsCaseInsensitive() {
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.LENIENT, "oak_log", MatchMode.EXACT, "Oak_Log"));
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.LENIENT, "OAK", MatchMode.PREFIX, "Oak_Log"));
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.LENIENT, "K_l", MatchMode.CONTAINS, "Oak_Log"));
        }

        @Test
        void emptyTargetMatchesEverythingEvenUnderExact() {
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.LENIENT, "", MatchMode.EXACT, "Oak_Log"));
            assertTrue(ObjectiveMatch.targetMatches(MatchFlavor.LENIENT, "", MatchMode.PREFIX, "anything"));
        }

        @Test
        void aNonMatchStillFails() {
            assertFalse(ObjectiveMatch.targetMatches(MatchFlavor.LENIENT, "birch", MatchMode.EXACT, "Oak_Log"));
        }
    }

    @Nested
    class Qualifiers {

        @Test
        void nullAuthoredQualifierMeansAnyInBothDialects() {
            for (MatchFlavor flavor : MatchFlavor.values()) {
                assertTrue(ObjectiveMatch.qualifierMatches(flavor, null, null));
                assertTrue(ObjectiveMatch.qualifierMatches(flavor, null, ""));
                assertTrue(ObjectiveMatch.qualifierMatches(flavor, null, "elite"));
            }
        }

        @Test
        void emptyAuthoredQualifierIsTheOneDivergence() {
            // Strict: an absent OR empty event qualifier both count as unqualified.
            assertTrue(ObjectiveMatch.qualifierMatches(MatchFlavor.STRICT, "", null));
            assertTrue(ObjectiveMatch.qualifierMatches(MatchFlavor.STRICT, "", ""));
            // Lenient: only an absent one.
            assertTrue(ObjectiveMatch.qualifierMatches(MatchFlavor.LENIENT, "", null));
            assertFalse(ObjectiveMatch.qualifierMatches(MatchFlavor.LENIENT, "", ""));
        }

        @Test
        void emptyAuthoredQualifierNeverMatchesAQualifiedEvent() {
            for (MatchFlavor flavor : MatchFlavor.values()) {
                assertFalse(ObjectiveMatch.qualifierMatches(flavor, "", "elite"));
            }
        }

        @Test
        void aNamedQualifierComparesCaseInsensitivelyInBothDialects() {
            for (MatchFlavor flavor : MatchFlavor.values()) {
                assertTrue(ObjectiveMatch.qualifierMatches(flavor, "Elite", "elite"));
                assertFalse(ObjectiveMatch.qualifierMatches(flavor, "Elite", "normal"));
                assertFalse(ObjectiveMatch.qualifierMatches(flavor, "Elite", null));
            }
        }
    }

    @Nested
    class Zones {

        @Test
        void anUnscopedObjectivePassesEverywhere() {
            assertTrue(ObjectiveMatch.zoneMatches(null, null));
            assertTrue(ObjectiveMatch.zoneMatches("  ", null));
            assertTrue(ObjectiveMatch.zoneMatches(null, new ZoneRef("Grove", "North")));
        }

        @Test
        void aScopedObjectiveNeverPassesForAnUnplaceableEvent() {
            assertFalse(ObjectiveMatch.zoneMatches("Grove", null));
        }

        @Test
        void eitherNameSatisfiesTheScopeCaseInsensitively() {
            ZoneRef where = new ZoneRef("Emerald_Grove", "Northlands");
            assertTrue(ObjectiveMatch.zoneMatches("emerald_grove", where));
            assertTrue(ObjectiveMatch.zoneMatches("NORTHLANDS", where));
            assertFalse(ObjectiveMatch.zoneMatches("Desert", where));
        }

        @Test
        void aZoneRefWithNeitherNameIsEmpty() {
            assertTrue(new ZoneRef(null, "  ").isEmpty());
            assertFalse(new ZoneRef("Grove", null).isEmpty());
        }
    }

    @Test
    void combinedMatchRequiresBothTargetAndQualifier() {
        assertTrue(ObjectiveMatch.matches(MatchFlavor.STRICT, "Wolf", MatchMode.EXACT, "elite",
                "Wolf", "Elite"));
        assertFalse(ObjectiveMatch.matches(MatchFlavor.STRICT, "Wolf", MatchMode.EXACT, "elite",
                "Wolf", "normal"));
        assertFalse(ObjectiveMatch.matches(MatchFlavor.STRICT, "Wolf", MatchMode.EXACT, "elite",
                "Bear", "Elite"));
    }

    @Test
    void matchModeParsesForgivinglyAndDefaultsToContains() {
        assertTrue(MatchMode.fromString(null) == MatchMode.CONTAINS);
        assertTrue(MatchMode.fromString("nonsense") == MatchMode.CONTAINS);
        assertTrue(MatchMode.fromString(" exact ") == MatchMode.EXACT);
        assertTrue(MatchMode.fromString("PREFIX") == MatchMode.PREFIX);
    }
}
