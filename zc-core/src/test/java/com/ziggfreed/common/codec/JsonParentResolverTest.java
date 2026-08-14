package com.ziggfreed.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Unit tests for {@link JsonParentResolver}: single + multi-level chains, per-leaf child-over-parent
 * merge with wholesale array replace, {@code Parent} stripping, cycle + unknown-parent standalone
 * fallback with a warning, pool-vs-output separation, and lower-cased ids.
 */
class JsonParentResolverTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void childOverridesParentPerLeafAndParentIsStripped() {
        Map<String, JsonObject> pool = new LinkedHashMap<>();
        pool.put("Base", obj("{\"A\":1,\"Group\":{\"X\":1,\"Y\":2},\"List\":[1,2]}"));
        pool.put("Child", obj("{\"Parent\":\"Base\",\"Group\":{\"Y\":9},\"List\":[3]}"));
        Map<String, JsonObject> out = JsonParentResolver.resolve(pool, List.of("child"), "Parent", s -> { });

        JsonObject child = out.get("child");
        assertEquals(1, child.get("A").getAsInt());                        // inherited
        assertEquals(1, child.getAsJsonObject("Group").get("X").getAsInt()); // nested inherit
        assertEquals(9, child.getAsJsonObject("Group").get("Y").getAsInt()); // nested override
        assertEquals(1, child.getAsJsonArray("List").size());               // arrays replace wholesale
        assertEquals(3, child.getAsJsonArray("List").get(0).getAsInt());
        assertFalse(child.has("Parent"));
    }

    @Test
    void aReplaceKeyAuthoredByTheChildDropsTheParentsCopyWhole() {
        Map<String, JsonObject> pool = new LinkedHashMap<>();
        pool.put("Base", obj("{\"Where\":{\"Match\":[\"default\"],\"ExcludeMatch\":[\"*Arena*\"]},\"A\":1}"));
        pool.put("Child", obj("{\"Parent\":\"Base\",\"Where\":{\"GameplayConfig\":[\"ForgottenTemple\"]}}"));
        pool.put("Silent", obj("{\"Parent\":\"Base\",\"A\":2}"));
        Map<String, JsonObject> out = JsonParentResolver.resolve(
                pool, pool.keySet(), "Parent", s -> { }, java.util.Set.of("Where"));

        JsonObject child = out.get("child").getAsJsonObject("Where");
        assertFalse(child.has("Match"),
                "a child retargeting its Where must not inherit the parent's Match underneath it"
                        + " (that shape once stood a duplicate NPC in a world nobody authored it for)");
        assertFalse(child.has("ExcludeMatch"), "the selector moves as ONE predicate");
        assertEquals("ForgottenTemple", child.getAsJsonArray("GameplayConfig").get(0).getAsString());
        assertEquals(1, out.get("child").get("A").getAsInt(), "non-replace keys still deep-merge");

        JsonObject silent = out.get("silent").getAsJsonObject("Where");
        assertEquals("default", silent.getAsJsonArray("Match").get(0).getAsString(),
                "a child that omits a replace-key inherits the parent's copy whole");
        assertEquals("*Arena*", silent.getAsJsonArray("ExcludeMatch").get(0).getAsString());
    }

    @Test
    void multiLevelChainResolvesTransitively() {
        Map<String, JsonObject> pool = new LinkedHashMap<>();
        pool.put("a", obj("{\"A\":1,\"B\":1,\"C\":1}"));
        pool.put("b", obj("{\"Parent\":\"a\",\"B\":2}"));
        pool.put("c", obj("{\"Parent\":\"b\",\"C\":3}"));
        Map<String, JsonObject> out = JsonParentResolver.resolve(pool, pool.keySet(), "Parent", s -> { });

        JsonObject c = out.get("c");
        assertEquals(1, c.get("A").getAsInt());
        assertEquals(2, c.get("B").getAsInt());
        assertEquals(3, c.get("C").getAsInt());
    }

    @Test
    void cycleWarnsAndResolvesStandalone() {
        Map<String, JsonObject> pool = new LinkedHashMap<>();
        pool.put("a", obj("{\"Parent\":\"b\",\"A\":1}"));
        pool.put("b", obj("{\"Parent\":\"a\",\"B\":2}"));
        List<String> warnings = new ArrayList<>();
        Map<String, JsonObject> out = JsonParentResolver.resolve(pool, pool.keySet(), "Parent", warnings::add);

        assertFalse(warnings.isEmpty());
        // both bodies still resolve (standalone at the cycle break) with Parent stripped
        assertEquals(2, out.size());
        assertFalse(out.get("a").has("Parent"));
        assertFalse(out.get("b").has("Parent"));
        assertEquals(1, out.get("a").get("A").getAsInt());
    }

    @Test
    void unknownParentWarnsAndResolvesStandalone() {
        Map<String, JsonObject> pool = new LinkedHashMap<>();
        pool.put("a", obj("{\"Parent\":\"nope\",\"A\":1}"));
        List<String> warnings = new ArrayList<>();
        Map<String, JsonObject> out = JsonParentResolver.resolve(pool, pool.keySet(), "Parent", warnings::add);

        assertEquals(1, warnings.size());
        assertEquals(1, out.get("a").get("A").getAsInt());
        assertFalse(out.get("a").has("Parent"));
    }

    @Test
    void poolOnlyBasesAreNotEmittedAndIdsLowerCase() {
        Map<String, JsonObject> pool = new LinkedHashMap<>();
        pool.put("Shared_Base", obj("{\"A\":1}"));
        pool.put("Leaf", obj("{\"Parent\":\"SHARED_BASE\",\"B\":2}"));
        Map<String, JsonObject> out = JsonParentResolver.resolve(pool, List.of("Leaf"), "Parent", s -> { });

        assertEquals(1, out.size());
        assertTrue(out.containsKey("leaf"));
        assertEquals(1, out.get("leaf").get("A").getAsInt());
    }

    @Test
    void sourceBodiesAreNeverMutated() {
        Map<String, JsonObject> pool = new LinkedHashMap<>();
        JsonObject childRaw = obj("{\"Parent\":\"base\",\"B\":2}");
        pool.put("base", obj("{\"A\":1}"));
        pool.put("child", childRaw);
        JsonParentResolver.resolve(pool, pool.keySet(), "Parent", s -> { });

        assertTrue(childRaw.has("Parent")); // the raw pool body keeps its Parent
        assertFalse(childRaw.has("A"));
    }
}
