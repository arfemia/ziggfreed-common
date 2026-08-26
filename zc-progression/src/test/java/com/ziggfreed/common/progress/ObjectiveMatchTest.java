package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The behaviour table for the ONE matching dialect every engine in this family runs: forgiving on
 * case, match-all on an empty target, and "specifically unqualified" on an empty qualifier. Each
 * row is a boundary a future "simplification" would quietly change, so this is the test that has to
 * fail first.
 */
class ObjectiveMatchTest {

    @Nested
    class Targets {

        @Test
        void everyModeIsCaseInsensitive() {
            assertTrue(ObjectiveMatch.targetMatches("oak_log", MatchMode.EXACT, "Oak_Log"));
            assertTrue(ObjectiveMatch.targetMatches("OAK", MatchMode.PREFIX, "Oak_Log"));
            assertTrue(ObjectiveMatch.targetMatches("K_l", MatchMode.CONTAINS, "Oak_Log"));
        }

        @Test
        void emptyTargetMatchesEverythingEvenUnderExact() {
            assertTrue(ObjectiveMatch.targetMatches("", MatchMode.EXACT, "Oak_Log"));
            assertTrue(ObjectiveMatch.targetMatches("", MatchMode.PREFIX, "anything"));
            assertTrue(ObjectiveMatch.targetMatches("", MatchMode.CONTAINS, "Oak_Log"));
        }

        @Test
        void aNonMatchStillFails() {
            assertFalse(ObjectiveMatch.targetMatches("birch", MatchMode.EXACT, "Oak_Log"));
            assertFalse(ObjectiveMatch.targetMatches("log", MatchMode.PREFIX, "Oak_Log"));
            assertFalse(ObjectiveMatch.targetMatches("birch", MatchMode.CONTAINS, "Oak_Log"));
        }
    }

    @Nested
    class Qualifiers {

        @Test
        void nullAuthoredQualifierMeansAny() {
            assertTrue(ObjectiveMatch.qualifierMatches(null, null));
            assertTrue(ObjectiveMatch.qualifierMatches(null, ""));
            assertTrue(ObjectiveMatch.qualifierMatches(null, "elite"));
        }

        @Test
        void emptyAuthoredQualifierAcceptsOnlyAnAbsentOne() {
            assertTrue(ObjectiveMatch.qualifierMatches("", null));
            assertFalse(ObjectiveMatch.qualifierMatches("", ""));
            assertFalse(ObjectiveMatch.qualifierMatches("", "elite"));
        }

        @Test
        void aNamedQualifierComparesCaseInsensitively() {
            assertTrue(ObjectiveMatch.qualifierMatches("Elite", "elite"));
            assertFalse(ObjectiveMatch.qualifierMatches("Elite", "normal"));
            assertFalse(ObjectiveMatch.qualifierMatches("Elite", null));
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
        assertTrue(ObjectiveMatch.matches("Wolf", MatchMode.EXACT, "elite", "Wolf", "Elite"));
        assertFalse(ObjectiveMatch.matches("Wolf", MatchMode.EXACT, "elite", "Wolf", "normal"));
        assertFalse(ObjectiveMatch.matches("Wolf", MatchMode.EXACT, "elite", "Bear", "Elite"));
    }

    @Test
    void matchModeParsesForgivinglyAndDefaultsToContains() {
        assertTrue(MatchMode.fromString(null) == MatchMode.CONTAINS);
        assertTrue(MatchMode.fromString("nonsense") == MatchMode.CONTAINS);
        assertTrue(MatchMode.fromString(" exact ") == MatchMode.EXACT);
        assertTrue(MatchMode.fromString("PREFIX") == MatchMode.PREFIX);
    }
}
