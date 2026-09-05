package com.ziggfreed.common.encounter.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The two shapes a spawner prefab comes in: the one the engine's own prefab save writes (per-block
 * {@code components} state, which a builder-page paste needs) and the bare one a hand-written file
 * has, which pastes an egg sack with no marker under it.
 */
class EncounterPrefabAuditTest {

    private static final Set<String> SPAWNERS = Set.of("Queen_Spawner", "Brood_Spawner");

    private static JsonObject prefab(String blocks) {
        return JsonParser.parseString("{\"version\": 8, \"blockIdVersion\": 11, \"anchorX\": 0, \"anchorY\": 0,"
                + " \"anchorZ\": 0, \"blocks\": [" + blocks + "]}").getAsJsonObject();
    }

    private static final String WITH_STATE = "{\"x\": 0, \"y\": 1, \"z\": 0, \"name\": \"Queen_Spawner\","
            + " \"components\": {\"Components\": {\"SpawnMarkerBlock\": {\"Config\": {\"SpawnMarker\": \"Queen_Marker\","
            + " \"MarkerOffset\": {\"X\": 0, \"Y\": 1, \"Z\": 0}}}}}}";

    private static final String BARE = "{\"x\": 4, \"y\": 1, \"z\": 3, \"name\": \"Brood_Spawner\"}";

    private static final String DECO = "{\"x\": 1, \"y\": 1, \"z\": 1, \"name\": \"Sand\"}";

    @Test
    void aPrefabWhoseSpawnerBlocksCarryTheirStateIsClean() {
        assertTrue(EncounterPrefabAudit.audit("Pack/Arena", prefab(WITH_STATE + ", " + DECO), SPAWNERS::contains)
                .isEmpty());
    }

    @Test
    void aBareSpawnerBlockIsReportedAgainstThePrefabAndNamesTheBlock() {
        List<Finding> findings = EncounterPrefabAudit.audit("Pack/Arena", prefab(WITH_STATE + ", " + BARE + ", " + DECO),
                SPAWNERS::contains);
        assertEquals(1, findings.size(), "one finding per bare spawner block, none for the decorated one or the sand");
        Finding finding = findings.getFirst();
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals(EncounterValidator.PREFAB_SPAWNER_WITHOUT_STATE, finding.code());
        assertEquals(EncounterValidator.DOMAIN, finding.domain());
        assertEquals("Pack/Arena", finding.sourceId(), "the prefab is the source");
        assertTrue(finding.message().contains("'Brood_Spawner'") && finding.message().contains("(4, 1, 3)"),
                "the block and where it sits are named: " + finding.message());
        assertTrue(finding.message().contains("prefab page"), "the message says which paste makes no marker");
    }

    @Test
    void aBlockThatIsNoSpawnerNeedsNoState() {
        assertTrue(EncounterPrefabAudit.audit("Pack/Deco", prefab(DECO + ", " + BARE), name -> false).isEmpty());
    }

    @Test
    void aFileWithNoBlocksReportsNothing() {
        assertTrue(EncounterPrefabAudit.audit("Pack/Empty", JsonParser.parseString("{\"version\": 8}").getAsJsonObject(),
                SPAWNERS::contains).isEmpty());
    }
}
