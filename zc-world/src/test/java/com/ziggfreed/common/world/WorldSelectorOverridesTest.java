package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The owner layer exists for one scenario, so that is what these pin: a server whose main world is
 * not called {@code default} re-points the shipped selector by id and everything aimed at that
 * name follows, without editing a jar or a pack.
 */
class WorldSelectorOverridesTest {

    private static Map<String, WorldSelectorDef> parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return WorldSelectorOverrides.parse(root);
    }

    @Test
    void anOwnerEntryRepointsTheShippedSelectorById() {
        Map<String, WorldSelectorDef> layer = parse("""
                { "Zc_Default": { "Names": ["default"], "Match": ["my_server_world"] } }
                """);

        WorldSelectorDef def = layer.get("zc_default");
        assertNotNull(def, "ids are lower-cased so an owner's casing never splits the entry");
        assertEquals(List.of("default"), def.names());
        assertNotNull(def.rankFor("my_server_world", null));
        assertNull(def.rankFor("default", null), "the entry REPLACES the shipped pattern wholesale");
    }

    @Test
    void everyAxisRoundTrips() {
        WorldSelectorDef def = parse("""
                { "My_Outdoors": { "Names": ["outdoor"], "Match": ["*"],
                                   "GameplayConfig": ["Overworld"], "ExcludeNames": ["arena"] } }
                """).get("my_outdoors");

        assertNotNull(def);
        assertEquals(List.of("outdoor"), def.names());
        assertEquals(List.of("arena"), def.excludes());
        assertEquals(MatchRank.gameplayConfig(), def.rankFor("whatever", "Overworld"));
    }

    @Test
    void aBareStringIsAcceptedAsAOneEntryList() {
        WorldSelectorDef def = parse("""
                { "My_World": { "Names": "mine", "Match": "my_world" } }
                """).get("my_world");

        assertNotNull(def, "refusing the shape an owner writes first would cost them a selector");
        assertEquals(List.of("mine"), def.names());
        assertNotNull(def.rankFor("my_world", null));
    }

    @Test
    void documentationKeysAndNonObjectEntriesAreSkipped() {
        Map<String, WorldSelectorDef> layer = parse("""
                { "$Comment": "why this file exists", "junk": 3,
                  "Real": { "Names": ["real"], "Match": ["real"] } }
                """);

        assertEquals(Map.of("real", layer.get("real")).keySet(), layer.keySet());
    }

    @Test
    void aMissingFilePublishesAnEmptyLayerRatherThanFailing(@TempDir Path tmp) {
        WorldSelectorOverrides overrides = WorldSelectorOverrides.getInstance();
        Path original = overrides.getFile();
        try {
            overrides.setFile(tmp.resolve("absent.json"));
            assertTrue(overrides.read().isEmpty());
        } finally {
            overrides.setFile(original);
            WorldSelectorConfig.getInstance().mergeOwnerLayer(Map.of());
        }
    }

    @Test
    void aMalformedFileIsTreatedAsEmptyAndNeverRewritten(@TempDir Path tmp) throws IOException {
        WorldSelectorOverrides overrides = WorldSelectorOverrides.getInstance();
        Path original = overrides.getFile();
        Path broken = tmp.resolve("world-selectors.json");
        String body = "{ this is not json";
        Files.writeString(broken, body, StandardCharsets.UTF_8);
        try {
            overrides.setFile(broken);

            assertTrue(overrides.read().isEmpty(), "a hand-edit typo costs the overrides, not the file");
            assertEquals(body, Files.readString(broken, StandardCharsets.UTF_8));
        } finally {
            overrides.setFile(original);
            WorldSelectorConfig.getInstance().mergeOwnerLayer(Map.of());
        }
    }

    @Test
    void loadingPublishesTheOwnerLayerOverThePackLayer(@TempDir Path tmp) throws IOException {
        WorldSelectorOverrides overrides = WorldSelectorOverrides.getInstance();
        WorldSelectorConfig config = WorldSelectorConfig.getInstance();
        Path original = overrides.getFile();
        Path file = tmp.resolve("world-selectors.json");
        Files.writeString(file,
                "{ \"Zc_Default\": { \"Names\": [\"default\"], \"Match\": [\"my_server_world\"] } }",
                StandardCharsets.UTF_8);
        try {
            config.mergePackLayer(Map.of("zc_default",
                    new WorldSelectorDef("zc_default", new String[]{"default"},
                            new String[]{"default"}, null, null)));
            overrides.setFile(file);

            assertTrue(WorldIdentity.resolve("my_server_world", null, config.all().values())
                    .has("default"), "the owner entry wins, so the renamed main world carries the name");
            assertTrue(WorldIdentity.resolve("default", null, config.all().values()).isEmpty());
        } finally {
            overrides.setFile(original);
            config.mergeOwnerLayer(Map.of());
            config.mergePackLayer(Map.of());
        }
    }
}
