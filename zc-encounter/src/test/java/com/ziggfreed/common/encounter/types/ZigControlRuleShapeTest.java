package com.ziggfreed.common.encounter.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The shipped scripts hand {@code ZigControlRule} what its builder reads. A unit JVM cannot load a script through the
 * engine's builder manager (the boot does), so the seam is pinned where it is written: the phase macro's
 * {@code SetControl} and {@code Control} reach the action's {@code Enabled} and {@code Effect} with the rule set before
 * the role swap, off unless written, and the base clears standing rules as the fight engages (no {@code Effect}, the
 * boss's slot), before the engaged beat, so a leashed boss never carries a phase into its next run.
 */
class ZigControlRuleShapeTest {

    private static final Path SCRIPTS = Path.of("src", "main", "resources", "Server", "EncounterManager");

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Every action list (a JSON array of objects carrying a {@code Type}) holding a {@code ZigControlRule}. */
    private static List<JsonArray> listsWithRule(JsonElement node) {
        List<JsonArray> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(JsonElement node, List<JsonArray> out) {
        if (node.isJsonArray()) {
            JsonArray array = node.getAsJsonArray();
            if (indexOf(array, EncounterTypes.CONTROL_RULE) >= 0) {
                out.add(array);
            }
            array.forEach(child -> collect(child, out));
        } else if (node.isJsonObject()) {
            node.getAsJsonObject().entrySet().forEach(entry -> collect(entry.getValue(), out));
        }
    }

    private static int indexOf(JsonArray actions, String type) {
        for (int i = 0; i < actions.size(); i++) {
            JsonElement action = actions.get(i);
            if (action.isJsonObject() && action.getAsJsonObject().has("Type")
                    && action.getAsJsonObject().get("Type").isJsonPrimitive()
                    && type.equals(action.getAsJsonObject().get("Type").getAsString())) {
                return i;
            }
        }
        return -1;
    }

    private static String computeOf(JsonObject action, String key) {
        JsonElement value = action.get(key);
        assertNotNull(value, "the action writes " + key);
        assertTrue(value.isJsonObject() && value.getAsJsonObject().has("Compute"), key + " is computed");
        return value.getAsJsonObject().get("Compute").getAsString();
    }

    @Test
    void thePhaseMacroSetsTheRuleBeforeTheRoleSwapAndOnlyWhenAsked() throws IOException {
        JsonObject macro = read(SCRIPTS.resolve("Macros").resolve("Zc_Phase_At_Health.json"));
        JsonObject parameters = macro.getAsJsonObject("Parameters");
        assertFalse(parameters.getAsJsonObject("SetControl").get("Value").getAsBoolean(), "off unless written");
        assertEquals("", parameters.getAsJsonObject("Control").get("Value").getAsString(), "no rule unless named");

        List<JsonArray> lists = listsWithRule(macro);
        assertEquals(1, lists.size(), "one rule action in the macro");
        JsonArray actions = lists.get(0);
        int rule = indexOf(actions, EncounterTypes.CONTROL_RULE);
        int swap = indexOf(actions, "ChangeTargetRole");
        assertTrue(swap > rule, "the rule goes on before the role swap reissues the boss");
        JsonObject action = actions.get(rule).getAsJsonObject();
        assertEquals("Slot", computeOf(action, BuilderActionZigControlRule.TARGET_SLOT));
        assertEquals("Control", computeOf(action, BuilderActionZigControlRule.EFFECT));
        assertEquals("SetControl", computeOf(action, "Enabled"));
    }

    @Test
    void theBaseClearsStandingRulesAsTheFightEngages() throws IOException {
        JsonObject base = read(SCRIPTS.resolve("Zc_Encounter_Base.json"));
        List<JsonArray> lists = listsWithRule(base);
        assertEquals(1, lists.size(), "one rule action in the base");
        JsonArray actions = lists.get(0);
        int rule = indexOf(actions, EncounterTypes.CONTROL_RULE);
        int engaged = -1;
        for (int i = 0; i < actions.size(); i++) {
            JsonObject action = actions.get(i).getAsJsonObject();
            if (action.has("SignalId") && "zc:engaged".equals(action.get("SignalId").getAsString())) {
                engaged = i;
            }
        }
        assertTrue(engaged > rule, "the clear runs in the engage, before the engaged beat");
        JsonObject action = actions.get(rule).getAsJsonObject();
        assertEquals("SubjectSlot", computeOf(action, BuilderActionZigControlRule.TARGET_SLOT));
        assertFalse(action.has(BuilderActionZigControlRule.EFFECT), "the engage puts no rule on, only clears");
        assertFalse(action.has("Enabled"), "the engage always clears");
    }
}
