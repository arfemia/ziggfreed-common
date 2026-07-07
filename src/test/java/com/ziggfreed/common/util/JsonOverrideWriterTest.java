package com.ziggfreed.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Unit tests for {@link JsonOverrideWriter}: dotted-PascalCase leaf set/remove preserving siblings +
 * {@code $Comment}, number type fidelity (Integer vs Double), top-level array upsert/replace/remove by
 * match field, the read-back helper, missing-file creation, and the malformed-file fail-safe.
 */
class JsonOverrideWriterTest {

    @TempDir Path dir;

    private Path file() {
        return dir.resolve("mob-scaling.json");
    }

    private JsonObject readBack(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private String rawText(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void missingFileCreatesObjectWithLeaf() throws IOException {
        Path f = file();
        assertTrue(JsonOverrideWriter.setLeaf(f, "Intensity", 2.0));
        assertTrue(Files.exists(f));
        assertEquals(2.0, readBack(f).get("Intensity").getAsDouble(), 1e-9);
    }

    @Test
    void setLeafPreservesCommentAndSiblings() throws IOException {
        Path f = file();
        Files.writeString(f, "{\n  \"$Comment\": \"keep me\",\n  \"Enabled\": true\n}\n", StandardCharsets.UTF_8);
        assertTrue(JsonOverrideWriter.setLeaf(f, "Intensity", 1.5));
        JsonObject root = readBack(f);
        assertEquals("keep me", root.get("$Comment").getAsString());
        assertTrue(root.get("Enabled").getAsBoolean());
        assertEquals(1.5, root.get("Intensity").getAsDouble(), 1e-9);
        // $Comment stays first (Gson preserves insertion order; the read root had it first).
        assertEquals("$Comment", root.keySet().iterator().next());
    }

    @Test
    void nestedLeavesShareParentAndPreserveExistingNestedSiblings() throws IOException {
        Path f = file();
        Files.writeString(f, "{\n  \"ZoneHud\": { \"OffsetY\": 3, \"Enabled\": true }\n}\n", StandardCharsets.UTF_8);
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put("ZoneHud.Position", "TOP_RIGHT");
        leaves.put("ZoneHud.OffsetX", 40);
        assertTrue(JsonOverrideWriter.setLeaves(f, leaves));
        JsonObject zone = readBack(f).getAsJsonObject("ZoneHud");
        assertEquals("TOP_RIGHT", zone.get("Position").getAsString());
        assertEquals(40, zone.get("OffsetX").getAsInt());
        assertEquals(3, zone.get("OffsetY").getAsInt()); // untouched sibling survives
        assertTrue(zone.get("Enabled").getAsBoolean());   // untouched sibling survives
    }

    @Test
    void integerStaysIntegerAndDoubleStaysDouble() throws IOException {
        Path f = file();
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put("ZoneHud.OffsetX", 16);   // Codec.INTEGER -> "16"
        leaves.put("Intensity", 2.0);        // Codec.DOUBLE  -> "2.0"
        assertTrue(JsonOverrideWriter.setLeaves(f, leaves));
        String text = rawText(f);
        assertTrue(text.contains("\"OffsetX\": 16"), text);
        assertFalse(text.contains("16.0"), text);
        assertTrue(text.contains("\"Intensity\": 2.0"), text);
    }

    @Test
    void nullValueRemovesLeaf() throws IOException {
        Path f = file();
        Files.writeString(f, "{\n  \"ZoneHud\": { \"Position\": \"TOP_LEFT\", \"OffsetX\": 5 }\n}\n",
                StandardCharsets.UTF_8);
        assertTrue(JsonOverrideWriter.setLeaf(f, "ZoneHud.Position", null));
        JsonObject zone = readBack(f).getAsJsonObject("ZoneHud");
        assertFalse(zone.has("Position"));
        assertEquals(5, zone.get("OffsetX").getAsInt());
    }

    @Test
    void arrayUpsertAppendsWhenAbsent() throws IOException {
        Path f = file();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Intensity", 2.0);
        entry.put("Difficulty.MinCap", 60.0);
        assertTrue(JsonOverrideWriter.upsertArrayEntry(f, "WorldOverrides", "Match",
                "instance-dungeon_of_fear_iii*", entry));
        JsonArray arr = readBack(f).getAsJsonArray("WorldOverrides");
        assertEquals(1, arr.size());
        JsonObject e = arr.get(0).getAsJsonObject();
        assertEquals("instance-dungeon_of_fear_iii*", e.get("Match").getAsString());
        assertEquals("Match", e.keySet().iterator().next()); // Match written first
        assertEquals(2.0, e.get("Intensity").getAsDouble(), 1e-9);
        assertEquals(60.0, e.getAsJsonObject("Difficulty").get("MinCap").getAsDouble(), 1e-9);
    }

    @Test
    void arrayUpsertReplacesOneElementWholeAndPreservesOthers() throws IOException {
        Path f = file();
        // Two pre-existing owner entries (simulating already-authored overrides).
        Files.writeString(f, "{\n  \"WorldOverrides\": [\n"
                + "    { \"Match\": \"world_a\", \"Intensity\": 1.0 },\n"
                + "    { \"Match\": \"world_b\", \"Intensity\": 2.0, \"RaritySpawnChance\": 0.1 }\n"
                + "  ]\n}\n", StandardCharsets.UTF_8);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Intensity", 3.0); // note: RaritySpawnChance omitted -> whole-element replace drops it (by design)
        assertTrue(JsonOverrideWriter.upsertArrayEntry(f, "WorldOverrides", "Match", "WORLD_B", entry)); // case-insensitive
        JsonArray arr = readBack(f).getAsJsonArray("WorldOverrides");
        assertEquals(2, arr.size());
        JsonObject a = arr.get(0).getAsJsonObject();
        JsonObject b = arr.get(1).getAsJsonObject();
        assertEquals("world_a", a.get("Match").getAsString());
        assertEquals(1.0, a.get("Intensity").getAsDouble(), 1e-9); // other element untouched
        assertEquals("world_b", b.get("Match").getAsString());
        assertEquals(3.0, b.get("Intensity").getAsDouble(), 1e-9); // replaced whole
        assertFalse(b.has("RaritySpawnChance"));                   // whole-element replace, by design
    }

    @Test
    void arrayRemoveDropsOneAndKeepsOthers() throws IOException {
        Path f = file();
        Files.writeString(f, "{\n  \"WorldOverrides\": [\n"
                + "    { \"Match\": \"world_a\" },\n"
                + "    { \"Match\": \"world_b\" }\n"
                + "  ]\n}\n", StandardCharsets.UTF_8);
        assertTrue(JsonOverrideWriter.removeArrayEntry(f, "WorldOverrides", "Match", "world_a"));
        JsonArray arr = readBack(f).getAsJsonArray("WorldOverrides");
        assertEquals(1, arr.size());
        assertEquals("world_b", arr.get(0).getAsJsonObject().get("Match").getAsString());
    }

    @Test
    void removeAbsentEntryIsIdempotentSuccess() {
        Path f = file();
        assertTrue(JsonOverrideWriter.removeArrayEntry(f, "WorldOverrides", "Match", "nope"));
    }

    @Test
    void readArrayKeyValuesReturnsMatchesInOrder() throws IOException {
        Path f = file();
        Files.writeString(f, "{\n  \"WorldOverrides\": [\n"
                + "    { \"Match\": \"world_a\" },\n"
                + "    { \"Match\": \"world_b\" }\n"
                + "  ]\n}\n", StandardCharsets.UTF_8);
        assertEquals(List.of("world_a", "world_b"),
                JsonOverrideWriter.readArrayKeyValues(f, "WorldOverrides", "Match"));
    }

    @Test
    void malformedExistingFileFailsSafeWithoutOverwriting() throws IOException {
        Path f = file();
        String broken = "{ this is not valid json";
        Files.writeString(f, broken, StandardCharsets.UTF_8);
        assertFalse(JsonOverrideWriter.setLeaf(f, "Intensity", 2.0));
        assertEquals(broken, rawText(f)); // untouched
    }
}
