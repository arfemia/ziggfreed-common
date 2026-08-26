package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The owner file's FILE-level rules: {@code $}-prefixed keys are reserved markers rather than
 * placement ids, and {@code $SchemaVersion} gates whether the file is readable at all. The pure
 * key-resolution grammar lives in {@code PlacementGateChainTest}; this is only about reading the
 * file.
 */
class NpcPlacementOverridesFileTest {

    /** The singleton must not leak this test's temp file into another test's question. */
    @AfterEach
    void pointBackAtTheDefaultFile() {
        NpcPlacementOverrides.getInstance()
                .setFile(Path.of("mods", "ziggfreedcommon", "npc-placements.json"));
    }

    @Test
    @DisplayName("$SchemaVersion 1 is a reserved marker, not a placement id")
    void schemaVersionIsReserved(@TempDir Path dir) throws IOException {
        point(dir, """
                { "$SchemaVersion": 1,
                  "$Comment": "stop the hub while the event runs",
                  "mmo_hub": { "enabled": false } }
                """);

        NpcPlacementOverrides overrides = NpcPlacementOverrides.getInstance();
        assertFalse(overrides.isEnabled("mmo_hub"), "the entry beside the marker is in force");
        assertTrue(overrides.isEnabled("$SchemaVersion"),
                "no phantom entry was made of the marker");
    }

    @Test
    @DisplayName("a newer $SchemaVersion refuses the whole file rather than guessing at it")
    void aNewerSchemaVersionRefusesTheFile(@TempDir Path dir) throws IOException {
        point(dir, """
                { "$SchemaVersion": 2, "mmo_hub": { "enabled": false } }
                """);

        assertTrue(NpcPlacementOverrides.getInstance().isEnabled("mmo_hub"),
                "nothing in a future-shaped file is in force");
    }

    private static void point(Path dir, String json) throws IOException {
        Path file = dir.resolve("npc-placements.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        NpcPlacementOverrides.getInstance().setFile(file);
    }
}
