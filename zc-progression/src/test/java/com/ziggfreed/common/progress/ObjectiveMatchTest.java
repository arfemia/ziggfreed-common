package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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

        @Test
        void theTwoArgFormComparesWholeSoAPrefixOfTheEventNeverMatchesIt() {
            assertFalse(ObjectiveMatch.qualifierMatches("Elite_Pack", "Elite_Pack_Alpha"));
            assertTrue(ObjectiveMatch.qualifierMatches("Elite_Pack", MatchMode.EXACT, "elite_pack"));
            assertFalse(ObjectiveMatch.qualifierMatches("Elite_Pack", MatchMode.EXACT,
                    "Elite_Pack_Alpha"));
        }

        @Test
        void prefixAndContainsCountAFamilyOfQualifiersCaseInsensitively() {
            assertTrue(ObjectiveMatch.qualifierMatches("elite_pack", MatchMode.PREFIX,
                    "Elite_Pack_Alpha"));
            assertTrue(ObjectiveMatch.qualifierMatches("Elite_Pack", MatchMode.PREFIX, "Elite_Pack"));
            assertFalse(ObjectiveMatch.qualifierMatches("Pack", MatchMode.PREFIX, "Elite_Pack"));
            assertTrue(ObjectiveMatch.qualifierMatches("PACK", MatchMode.CONTAINS, "Elite_Pack_Alpha"));
            assertFalse(ObjectiveMatch.qualifierMatches("Normal", MatchMode.CONTAINS, "Elite_Pack"));
        }

        @Test
        void theNullAndEmptyRulesHoldWhateverTheMode() {
            for (MatchMode mode : MatchMode.values()) {
                assertTrue(ObjectiveMatch.qualifierMatches(null, mode, "anything"), mode + ": null means any");
                assertTrue(ObjectiveMatch.qualifierMatches("", mode, null), mode + ": empty accepts the unqualified");
                assertFalse(ObjectiveMatch.qualifierMatches("", mode, "elite"), mode + ": empty refuses a qualified one");
                assertFalse(ObjectiveMatch.qualifierMatches("Elite", mode, null), mode + ": a name needs a qualifier");
            }
        }
    }

    /**
     * {@code Qualifiers} beside {@code Qualifier}: the accepted qualifiers are the single one plus
     * every list entry, and an event matches when its qualifier matches ANY of them under the one
     * authored {@code QualifierMatchMode}. A union, because both leaves read "accept these".
     */
    @Nested
    class SeveralQualifiers {

        private static final List<String> REPEATABLE_OR_SEASONAL = List.of("REPEATABLE", "SEASONAL");

        @Test
        void aListAcceptsEachOfItsQualifiersAndRefusesAnother() {
            assertTrue(ObjectiveMatch.qualifierMatches(null, REPEATABLE_OR_SEASONAL, MatchMode.EXACT, "REPEATABLE"));
            assertTrue(ObjectiveMatch.qualifierMatches(null, REPEATABLE_OR_SEASONAL, MatchMode.EXACT, "seasonal"),
                    "each entry compares case-insensitively, as the single qualifier does");
            assertFalse(ObjectiveMatch.qualifierMatches(null, REPEATABLE_OR_SEASONAL, MatchMode.EXACT, "DAILY"));
            assertFalse(ObjectiveMatch.qualifierMatches(null, REPEATABLE_OR_SEASONAL, MatchMode.EXACT, null),
                    "a named qualifier still needs the event to carry one");
        }

        @Test
        void aQualifierAloneMatchesExactlyAsBefore() {
            String[] authored = {null, "", "Elite", "Elite_Pack"};
            String[] events = {null, "", "elite", "Elite_Pack_Alpha", "normal"};
            for (MatchMode mode : MatchMode.values()) {
                for (String a : authored) {
                    for (String e : events) {
                        assertEquals(ObjectiveMatch.qualifierMatches(a, mode, e),
                                ObjectiveMatch.qualifierMatches(a, List.of(), mode, e),
                                mode + " '" + a + "' vs '" + e + "': no list means today's answer");
                    }
                }
            }
        }

        @Test
        void neitherLeafAuthoredFiltersNothing() {
            assertTrue(ObjectiveMatch.qualifierMatches(null, List.of(), MatchMode.EXACT, "DAILY"));
            assertTrue(ObjectiveMatch.qualifierMatches(null, List.of(), MatchMode.EXACT, null));
        }

        @Test
        void qualifierPlusQualifiersAcceptsTheUnion() {
            List<String> seasonal = List.of("SEASONAL");
            assertTrue(ObjectiveMatch.qualifierMatches("NORMAL", seasonal, MatchMode.EXACT, "NORMAL"));
            assertTrue(ObjectiveMatch.qualifierMatches("NORMAL", seasonal, MatchMode.EXACT, "SEASONAL"));
            assertFalse(ObjectiveMatch.qualifierMatches("NORMAL", seasonal, MatchMode.EXACT, "DAILY"));

            assertTrue(ObjectiveMatch.qualifierMatches("", seasonal, MatchMode.EXACT, null),
                    "an empty Qualifier keeps its meaning (the unqualified kind) inside the union");
            assertTrue(ObjectiveMatch.qualifierMatches("", seasonal, MatchMode.EXACT, "Seasonal"));
            assertFalse(ObjectiveMatch.qualifierMatches("", seasonal, MatchMode.EXACT, "DAILY"));
        }

        @Test
        void aNonExactModeAppliesToEachEntry() {
            List<String> families = List.of("Elite_Pack", "Boss");
            assertTrue(ObjectiveMatch.qualifierMatches(null, families, MatchMode.PREFIX, "Elite_Pack_Alpha"));
            assertTrue(ObjectiveMatch.qualifierMatches(null, families, MatchMode.PREFIX, "boss_final"));
            assertFalse(ObjectiveMatch.qualifierMatches(null, families, MatchMode.PREFIX, "Final_Boss"),
                    "PREFIX reads each entry as a start, never anywhere inside");
            assertTrue(ObjectiveMatch.qualifierMatches(null, families, MatchMode.CONTAINS, "Final_Boss"));
            assertTrue(ObjectiveMatch.qualifierMatches("Normal", List.of("Elite"), MatchMode.PREFIX, "Elite_Alpha"),
                    "the single qualifier and the list share the one comparison");
            assertFalse(ObjectiveMatch.qualifierMatches(null, families, MatchMode.EXACT, "Elite_Pack_Alpha"));
        }

        @Test
        void blankEntriesAreIgnored() {
            List<String> blanks = new ArrayList<>(List.of(" ", ""));
            blanks.add(null);
            assertTrue(ObjectiveMatch.qualifierMatches(null, blanks, MatchMode.EXACT, "DAILY"),
                    "a list of nothing but blanks names no qualifier, so it filters nothing");
            assertTrue(ObjectiveMatch.qualifierMatches(null, blanks, MatchMode.EXACT, null));

            List<String> oneReal = List.of(" ", "SEASONAL");
            assertTrue(ObjectiveMatch.qualifierMatches(null, oneReal, MatchMode.EXACT, "SEASONAL"));
            assertFalse(ObjectiveMatch.qualifierMatches(null, oneReal, MatchMode.EXACT, "DAILY"));
            assertFalse(ObjectiveMatch.qualifierMatches(null, oneReal, MatchMode.EXACT, null),
                    "a blank entry is never read as the empty 'unqualified' value");
        }

        @Test
        void theWholePredicateAndTheDefCarryTheList() {
            assertTrue(ObjectiveMatch.matches("", MatchMode.CONTAINS, null, REPEATABLE_OR_SEASONAL,
                    MatchMode.EXACT, "Harvest_Feast_Pies", "SEASONAL"));
            assertFalse(ObjectiveMatch.matches("Wolf", MatchMode.EXACT, null, REPEATABLE_OR_SEASONAL,
                    MatchMode.EXACT, "Bear", "SEASONAL"), "the target still has to match");

            ObjectiveDef def = ObjectiveDef.builder("tiers", "COMPLETE_QUEST")
                    .qualifiers(REPEATABLE_OR_SEASONAL).build();
            assertEquals(REPEATABLE_OR_SEASONAL, def.qualifiers());
            assertTrue(def.matches("Any_Quest", "REPEATABLE"));
            assertTrue(def.matches("Any_Quest", "SEASONAL"));
            assertFalse(def.matches("Any_Quest", "DAILY"));

            ObjectiveDef blanksDropped = ObjectiveDef.builder("x", "K").qualifiers(List.of(" ", "A", "")).build();
            assertEquals(List.of("A"), blanksDropped.qualifiers(), "the resolved list never carries a blank");
            assertTrue(ObjectiveDef.builder("x", "K").build().qualifiers().isEmpty(),
                    "unauthored is an empty list, never null");
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
    void combinedMatchHonoursTheQualifiersOwnMode() {
        assertTrue(ObjectiveMatch.matches("Wolf", MatchMode.PREFIX, "Elite_Pack", MatchMode.PREFIX,
                "Wolf_Grey", "Elite_Pack_Alpha"));
        assertFalse(ObjectiveMatch.matches("Wolf", MatchMode.PREFIX, "Elite_Pack", MatchMode.EXACT,
                "Wolf_Grey", "Elite_Pack_Alpha"));
        ObjectiveDef def = ObjectiveDef.builder("unmake", "KILL_ENTITY")
                .target("Wolf").matchMode(MatchMode.PREFIX)
                .qualifier("Elite_Pack").qualifierMatchMode(MatchMode.PREFIX)
                .build();
        assertTrue(def.matches("Wolf_Grey", "Elite_Pack_Alpha"));
        assertTrue(def.matches("Wolf_Grey", "Elite_Pack"));
        assertFalse(def.matches("Wolf_Grey", "Normal"));
        assertTrue(ObjectiveDef.builder("x", "K").qualifier("A").build().qualifierMatchMode() == MatchMode.EXACT,
                "an objective that names no qualifier comparison compares it whole");
    }

    @Test
    void matchModeParsesForgivinglyAndDefaultsToContains() {
        assertTrue(MatchMode.fromString(null) == MatchMode.CONTAINS);
        assertTrue(MatchMode.fromString("nonsense") == MatchMode.CONTAINS);
        assertTrue(MatchMode.fromString(" exact ") == MatchMode.EXACT);
        assertTrue(MatchMode.fromString("PREFIX") == MatchMode.PREFIX);
    }

    @Test
    void matchModeParsesWithACallerChosenFallback() {
        assertTrue(MatchMode.fromString(null, MatchMode.EXACT) == MatchMode.EXACT);
        assertTrue(MatchMode.fromString("nonsense", MatchMode.EXACT) == MatchMode.EXACT);
        assertTrue(MatchMode.fromString(" contains ", MatchMode.EXACT) == MatchMode.CONTAINS);
    }
}
