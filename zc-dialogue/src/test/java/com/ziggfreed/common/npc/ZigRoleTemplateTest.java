package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * RAW JSON shape of the library's quest-giver role template, {@code Template_Zig_QuestGiver} (no
 * engine: the NPC builder registry does not exist in a unit JVM).
 *
 * <p>A consumer builds a giver as a native role {@code Variant} of this file, and the engine is
 * unforgiving about the contract between the two. A {@code Modify} key the template does not declare
 * throws ("Parameter X does not exist or is private"), so a declared parameter bound to nothing reads
 * as an offered knob and is a trap; hence the two-way check that every declared parameter is consumed
 * by a {@code Compute} and every {@code Compute} names a declared one. {@code Armor} and the
 * {@code SetInteractable} {@code Hint} are read literally (a plain string-array reader, and a guard that
 * refuses a computed value), so a {@code Compute} on either is refused when the role is built and the
 * NPC simply never appears. Both failures are silent in game, which is why they are pinned here.
 */
class ZigRoleTemplateTest {

    private static final String TEMPLATE = "/Server/NPC/Roles/Passive/Template_Zig_QuestGiver.json";

    /** Exactly the role fields the engine reads through a Holder, so a Variant can override them. */
    private static final Set<String> PARAMETERS = new LinkedHashSet<>(List.of(
            "Appearance", "NameTranslationKey", "Weapons", "OffHand", "DefaultOffHandSlot"));

    /** The keys a native role reads LITERALLY: a {@code Compute} at either one is refused at build time. */
    private static final Set<String> LITERAL_ONLY_KEYS = Set.of("Armor", "Hint");

    private static final String HINT_KEY = "npcs.Zig_QuestGiver.hint";
    private static final String NAME_KEY = "npcs.Zig_QuestGiver.name";

    private static JsonObject template() throws IOException {
        try (InputStream is = ZigRoleTemplateTest.class.getResourceAsStream(TEMPLATE)) {
            assertNotNull(is, "missing shipped role: " + TEMPLATE);
            JsonElement parsed = JsonParser.parseString(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(parsed.isJsonObject(), TEMPLATE + " must decode to a JSON object");
            return parsed.getAsJsonObject();
        }
    }

    @Test
    void itIsAnAbstractTemplate() throws IOException {
        JsonElement type = template().get("Type");
        assertNotNull(type, TEMPLATE + " must author a Type");
        assertEquals("Abstract", type.getAsString(),
                TEMPLATE + " must be Abstract, so it never spawns on its own");
    }

    @Test
    void itDeclaresExactlyTheFiveOverridableParameters() throws IOException {
        JsonObject role = template();
        assertEquals(PARAMETERS, parameterNames(role),
                TEMPLATE + " must declare exactly the parameters a Variant can actually override");

        JsonObject name = role.getAsJsonObject("Parameters").getAsJsonObject("NameTranslationKey");
        assertEquals(NAME_KEY, name.get("Value").getAsString(),
                TEMPLATE + " must default its nameplate to the library's own key");
    }

    @Test
    void everyParameterIsConsumedAndEveryBindingIsDeclared() throws IOException {
        JsonObject role = template();
        assertEquals(parameterNames(role), new LinkedHashSet<>(computeTargets(role)),
                TEMPLATE + " must consume every parameter it declares and declare every one it binds: "
                        + "a declared parameter bound to nothing throws when a Variant overrides it");
    }

    @Test
    void armorAndTheHintAreLiteral() throws IOException {
        JsonObject role = template();

        JsonElement armor = role.get("Armor");
        assertNotNull(armor, TEMPLATE + " must author a literal Armor list");
        assertTrue(armor.isJsonArray(), TEMPLATE + " Armor must be a literal array");
        for (JsonElement piece : armor.getAsJsonArray()) {
            assertTrue(piece.isJsonPrimitive() && !piece.getAsString().isBlank(),
                    TEMPLATE + " Armor must list only non-blank item ids, found " + piece);
        }

        List<String> hints = literalHints(role);
        assertFalse(hints.isEmpty(), TEMPLATE + " must author a literal SetInteractable Hint");
        for (String hint : hints) {
            assertEquals(HINT_KEY, hint, TEMPLATE + " press-F prompt must be the library's own key");
        }
    }

    @Test
    void noComputeSitsOnALiteralOnlyField() throws IOException {
        for (String key : computeBoundKeys(template())) {
            assertFalse(LITERAL_ONLY_KEYS.contains(key),
                    TEMPLATE + " binds Compute on '" + key + "', which the engine reads literally: "
                            + "the role is refused at build time and the NPC never appears");
        }
    }

    @Test
    void pressingFRunsThePlacementInteraction() throws IOException {
        JsonElement interaction = template().get("InteractionInstruction");
        assertNotNull(interaction, TEMPLATE + " must author an InteractionInstruction");

        List<JsonObject> actions = new ArrayList<>();
        collectActionsOfType(interaction, "ZigPlacementInteract", actions);
        assertEquals(1, actions.size(),
                TEMPLATE + " press F must run ZigPlacementInteract exactly once, so the placement picks the conversation");
        JsonElement open = actions.get(0).get("Open");
        assertNotNull(open, TEMPLATE + " ZigPlacementInteract must carry an Open for a giver nothing placed");
        assertEquals("Quests", open.getAsString(),
                TEMPLATE + " an unplaced giver must still offer its quest list");
    }

    // ==================== raw JSON walks ====================

    private static Set<String> parameterNames(JsonObject role) {
        JsonElement params = role.get("Parameters");
        if (params == null || !params.isJsonObject()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String key : params.getAsJsonObject().keySet()) {
            if (!key.startsWith("$")) {
                out.add(key);
            }
        }
        return out;
    }

    /** Every {@code Compute} target named anywhere under {@code el}, in encounter order. */
    private static List<String> computeTargets(JsonElement el) {
        List<String> out = new ArrayList<>();
        walk(el, obj -> {
            JsonElement compute = obj.get("Compute");
            if (compute != null && compute.isJsonPrimitive()) {
                out.add(compute.getAsString());
            }
        });
        return out;
    }

    /** Every key anywhere under {@code el} whose value is a {@code Compute} object. */
    private static List<String> computeBoundKeys(JsonElement el) {
        List<String> out = new ArrayList<>();
        walk(el, obj -> {
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonObject() && value.getAsJsonObject().has("Compute")) {
                    out.add(entry.getKey());
                }
            }
        });
        return out;
    }

    /** Every literal {@code Hint} string under {@code el}. */
    private static List<String> literalHints(JsonElement el) {
        List<String> out = new ArrayList<>();
        walk(el, obj -> {
            JsonElement hint = obj.get("Hint");
            if (hint != null && hint.isJsonPrimitive()) {
                out.add(hint.getAsString());
            }
        });
        return out;
    }

    private static void collectActionsOfType(JsonElement el, String type, List<JsonObject> out) {
        walk(el, obj -> {
            JsonElement t = obj.get("Type");
            if (t != null && t.isJsonPrimitive() && type.equals(t.getAsString())) {
                out.add(obj);
            }
        });
    }

    /** Visits every JSON object under {@code el}, depth first. */
    private static void walk(JsonElement el, Consumer<JsonObject> visitor) {
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (el.isJsonArray()) {
            el.getAsJsonArray().forEach(child -> walk(child, visitor));
            return;
        }
        if (!el.isJsonObject()) {
            return;
        }
        JsonObject obj = el.getAsJsonObject();
        visitor.accept(obj);
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            walk(entry.getValue(), visitor);
        }
    }
}
