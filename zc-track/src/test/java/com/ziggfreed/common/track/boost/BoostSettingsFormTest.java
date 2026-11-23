package com.ziggfreed.common.track.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The boost start command runs as the console (R1185), so the Boost Settings form keeps it to a server operator by
 * the game's own operator status alone: the field's whole {@code Requires} is one {@code hytale:operator} condition
 * with {@code Min} 1, never a permission node an admin could be granted. A structural guard on the shipped form file.
 */
class BoostSettingsFormTest {

    private static final String FORM = "/Server/ZiggfreedCommon/OwnerForms/Boost_Settings.json";

    @Test
    void theStartCommandIsAServerOperatorsAlone() throws IOException {
        JsonObject form;
        try (InputStream in = BoostSettingsFormTest.class.getResourceAsStream(FORM)) {
            assertNotNull(in, "ships " + FORM);
            form = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject command = null;
        for (JsonElement section : form.getAsJsonArray("Sections")) {
            for (JsonElement field : section.getAsJsonObject().getAsJsonArray("Fields")) {
                if ("OnStart.Command".equals(field.getAsJsonObject().get("Path").getAsString())) {
                    command = field.getAsJsonObject();
                }
            }
        }
        assertTrue(command != null && command.has("Requires"), "the command sits on the form behind a gate");
        JsonObject requires = command.getAsJsonObject("Requires");
        assertEquals(1, requires.size(), "the gate is the operator condition alone: " + requires);
        assertEquals(1, requires.getAsJsonArray("Factors").size(), "one condition: " + requires);
        JsonObject condition = requires.getAsJsonArray("Factors").get(0).getAsJsonObject();
        assertEquals("hytale:operator", condition.get("Factor").getAsString());
        assertEquals(1, condition.get("Min").getAsInt(), "Min 1, so a player who is not an operator is refused");
    }
}
