package com.ziggfreed.common.encounter.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Which rule a run takes: the subject axis first on the shared name ladder, the world axis as the
 * tie-break, first by id on a full tie, and a rule whose world or subject does not match is out.
 */
class ParticipationRulesTest {

    private static EncounterParticipationAsset rule(String id, String json) throws IOException {
        return EncounterBindingCodecTest.rule(json, id);
    }

    @Test
    void theMostSpecificSubjectMatchWins() throws IOException {
        EncounterParticipationAsset all = rule("all", "{\"Match\": \"*\"}");
        EncounterParticipationAsset family = rule("family", "{\"Match\": \"*Warden*\"}");
        EncounterParticipationAsset exact = rule("exact", "{\"Match\": \"KweebecNightmare_Warden\"}");
        List<EncounterParticipationAsset> rules = List.of(all, family, exact);
        assertEquals("exact", ParticipationRules.resolve("KweebecNightmare_Warden", "default", null, rules).getId());
        assertEquals("family", ParticipationRules.resolve("KweebecNightmare_Warden_Phase2", "default", null, rules).getId());
        assertEquals("all", ParticipationRules.resolve("Trork_Brawler", "default", null, rules).getId());
    }

    @Test
    void aRuleNamingTheWorldOutranksOneThatDoesNot() throws IOException {
        EncounterParticipationAsset everywhere = rule("everywhere", "{\"Match\": \"*Warden*\"}");
        EncounterParticipationAsset grove = rule("grove",
                "{\"Match\": \"*Warden*\", \"Where\": {\"GameplayConfig\": [\"KweebecNightmare\"]}}");
        List<EncounterParticipationAsset> rules = List.of(everywhere, grove);
        assertEquals("grove", ParticipationRules.resolve("Warden", "instance-x", "KweebecNightmare", rules).getId());
        assertEquals("everywhere", ParticipationRules.resolve("Warden", "default", "Default", rules).getId());
    }

    @Test
    void theSubjectAxisDecidesBeforeTheWorldAxis() throws IOException {
        EncounterParticipationAsset exactAnywhere = rule("exact", "{\"Match\": \"Warden\"}");
        EncounterParticipationAsset patternHere = rule("pattern",
                "{\"Match\": \"*Warden*\", \"Where\": {\"Match\": [\"default\"]}}");
        assertEquals("exact", ParticipationRules.resolve("Warden", "default", null,
                List.of(patternHere, exactAnywhere)).getId());
    }

    @Test
    void aDisabledRuleAndAWorldMismatchAreOut() throws IOException {
        EncounterParticipationAsset off = rule("off", "{\"Match\": \"Warden\", \"Enabled\": false}");
        EncounterParticipationAsset elsewhere = rule("elsewhere",
                "{\"Match\": \"Warden\", \"Where\": {\"Match\": [\"arena\"]}}");
        assertNull(ParticipationRules.resolve("Warden", "default", null, List.of(off, elsewhere)));
    }

    @Test
    void aRunWithNoSubjectTakesOnlyTheCatchAll() throws IOException {
        EncounterParticipationAsset all = rule("all", "{}");
        EncounterParticipationAsset named = rule("named", "{\"Match\": \"Warden\"}");
        assertEquals("all", ParticipationRules.resolve(null, "default", null, List.of(named, all)).getId());
        assertNull(ParticipationRules.resolve(null, "default", null, List.of(named)));
    }

    @Test
    void aFullTieKeepsTheFirstById() throws IOException {
        EncounterParticipationAsset a = rule("a", "{\"Match\": \"*Warden*\"}");
        EncounterParticipationAsset b = rule("b", "{\"Match\": \"*Warden*\"}");
        assertEquals("a", ParticipationRules.resolve("Warden", "default", null, List.of(a, b)).getId());
        assertEquals("b", ParticipationRules.resolve("Warden", "default", null, List.of(b, a)).getId());
    }
}
