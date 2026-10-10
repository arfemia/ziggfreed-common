package com.ziggfreed.common.encounter.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The encounter rule's decode contract: an empty row is enabled and names no role, {@code Match.Role}
 * decodes from its nested group, an owner overlay keeps every leaf it did not mention, a {@code Parent}
 * child inherits and overrides leaf by leaf, and the rows the library ships decode to the roles they name.
 */
public class EncounterRuleCodecTest {

    private static final Path SHIPPED = Path.of("src", "main", "resources", "Server", "ZiggfreedCommon",
            "EncounterRules");

    /** Decode {@code json} as the rule {@code id}, inheriting from {@code parent} (filed under {@code parentId}). */
    public static EncounterRuleAsset rule(String json, String id, String parentId, EncounterRuleAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(EncounterRuleAsset.class, id, parentId);
        return EncounterRuleAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }

    public static EncounterRuleAsset rule(String json, String id) throws IOException {
        return rule(json, id, null, null);
    }

    @Test
    void anEmptyRowIsEnabledAndNamesNoRole() throws IOException {
        EncounterRuleAsset row = rule("{}", "bare");
        assertTrue(row.isEnabled());
        assertNull(row.getRole());
        assertNull(row.getNameKey());
        assertEquals("bare", row.getId());
    }

    @Test
    void theRoleDecodesFromItsMatchGroupTrimmed() throws IOException {
        EncounterRuleAsset row = rule("{\"Match\": {\"Role\": \" Goblin_Feastmaster \"},"
                + " \"NameKey\": \"server.npcRoles.Goblin_Feastmaster.name\", \"Enabled\": false}", "feast");
        assertEquals("Goblin_Feastmaster", row.getRole());
        assertEquals("server.npcRoles.Goblin_Feastmaster.name", row.getNameKey());
        assertFalse(row.isEnabled());
        assertNull(rule("{\"Match\": {\"Role\": \"  \"}}", "blank").getRole(), "a blank role names none");
        assertEquals("goblin_feastmaster", EncounterRuleAsset.roleKey(" Goblin_Feastmaster "));
        assertNull(EncounterRuleAsset.roleKey(" "));
    }

    @Test
    void anOwnerOverlayKeepsEveryLeafItDidNotMention() throws IOException {
        EncounterRuleAsset pack = rule("{\"Match\": {\"Role\": \"Bear_Voidtaken\"}, \"NameKey\": \"bear.name\"}",
                "bear_voidtaken");
        EncounterRuleAsset owner = rule("{\"Enabled\": false}", "bear_voidtaken", "bear_voidtaken", pack);
        assertFalse(owner.isEnabled(), "the mentioned leaf changed");
        assertEquals("Bear_Voidtaken", owner.getRole(), "the unmentioned group survived");
        assertEquals("bear.name", owner.getNameKey(), "the unmentioned leaf survived");
        EncounterRuleAsset renamed = rule("{\"NameKey\": \"other.name\"}", "bear_voidtaken", "bear_voidtaken", pack);
        assertTrue(renamed.isEnabled(), "an overlay that says nothing of Enabled keeps it on");
        assertEquals("Bear_Voidtaken", renamed.getRole());
    }

    @Test
    void aParentChildInheritsWhatItDoesNotWriteAndOverridesWhatItDoes() throws IOException {
        EncounterRuleAsset base = rule("{\"Match\": {\"Role\": \"Goblin_Feastmaster\"}, \"NameKey\": \"base.name\"}",
                "Base_Rule");
        EncounterRuleAsset child = rule("{\"NameKey\": \"child.name\"}", "Child_Rule", "Base_Rule", base);
        assertEquals("Goblin_Feastmaster", child.getRole(), "the role came from the parent");
        assertEquals("child.name", child.getNameKey(), "the child's own leaf won");
        assertEquals("Child_Rule", child.getId());
        EncounterRuleAsset other = rule("{\"Match\": {\"Role\": \"Bear_Voidtaken\"}}", "Other_Rule", "Base_Rule", base);
        assertEquals("Bear_Voidtaken", other.getRole(), "a child naming its own role replaces the parent's");
        assertEquals("base.name", other.getNameKey());
    }

    @Test
    void theShippedRowsDecodeToTheRolesTheyAreNamedFor() throws IOException {
        // Each shipped row's role, and the game's own name key it reuses: the Skeleton Elite's second
        // phase has none of its own, so it reads the first phase's.
        Map<String, String> shipped = Map.of(
                "Goblin_Feastmaster", "server.npcRoles.Goblin_Feastmaster.name",
                "Bear_Voidtaken", "server.npcRoles.Bear_Voidtaken.name",
                "Skeleton_Elite_Phase_2", "server.npcRoles.Skeleton_Elite.name");
        try (Stream<Path> files = Files.list(SHIPPED)) {
            assertEquals(shipped.size(), files.filter(f -> f.toString().endsWith(".json")).count(),
                    "the library ships exactly these rows");
        }
        for (Map.Entry<String, String> entry : shipped.entrySet()) {
            String role = entry.getKey();
            String json = Files.readString(SHIPPED.resolve(role + ".json"), StandardCharsets.UTF_8);
            EncounterRuleAsset row = rule(json, role);
            assertEquals(role, row.getRole(), role + " names its own role");
            assertEquals(entry.getValue(), row.getNameKey(), role + " reuses the game's name key");
            assertTrue(row.isEnabled());
        }
    }
}
