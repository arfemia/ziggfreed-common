package com.ziggfreed.common.almanac.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.page.AlmanacMenuTab;
import com.ziggfreed.common.almanac.page.AlmanacMenuTab.Knobs;

/** The owner's word on the Almanac tab: {@code almanac.json}'s {@code $MenuTab} group beside {@code $Enabled}. */
class AlmanacMenuTabFileTest {

    @TempDir
    Path dir;

    @BeforeEach
    void point() {
        AlmanacOwnerLayers.setDirectory(dir);
        AlmanacMenuTab.resetForTests();
        AlmanacSwitch.resetForTests();
    }

    @AfterEach
    void restore() {
        AlmanacOwnerLayers.setDirectory(AlmanacOwnerLayers.DEFAULT_DIRECTORY);
        AlmanacMenuTab.resetForTests();
        AlmanacSwitch.resetForTests();
    }

    private void write(String body) throws IOException {
        Files.writeString(dir.resolve(AlmanacOwnerLayers.FILE), body, StandardCharsets.UTF_8);
    }

    private JsonObject read() throws IOException {
        return JsonParser.parseString(Files.readString(dir.resolve(AlmanacOwnerLayers.FILE), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    @Test
    void withNoGroupTheKnobsAreTheDefaults() throws IOException {
        AlmanacOwnerLayers.readSwitch();
        assertEquals(Knobs.DEFAULTS, AlmanacMenuTab.knobs());
        write("{ \"$Enabled\": true }");
        AlmanacOwnerLayers.readSwitch();
        assertEquals(Knobs.DEFAULTS, AlmanacMenuTab.knobs());
        assertFalse(AlmanacOwnerLayers.menuTabWritten());
    }

    @Test
    void eachKnobIsReadOnItsOwn() throws IOException {
        write("{ \"$MenuTab\": { \"OnlyWhileLive\": true } }");
        AlmanacOwnerLayers.readSwitch();
        assertEquals(new Knobs(true, true), AlmanacMenuTab.knobs());
        assertTrue(AlmanacOwnerLayers.menuTabWritten());

        write("{ \"$MenuTab\": { \"Show\": false } }");
        AlmanacOwnerLayers.readSwitch();
        assertEquals(new Knobs(false, false), AlmanacMenuTab.knobs());
    }

    @Test
    void aKnobThatIsNotTrueOrFalseKeepsItsDefault() throws IOException {
        write("{ \"$MenuTab\": { \"Show\": \"no\", \"OnlyWhileLive\": true } }");
        AlmanacOwnerLayers.readSwitch();
        assertEquals(new Knobs(true, true), AlmanacMenuTab.knobs());

        write("{ \"$MenuTab\": true }");
        AlmanacOwnerLayers.readSwitch();
        assertEquals(Knobs.DEFAULTS, AlmanacMenuTab.knobs());
    }

    @Test
    void writingPutsBothKnobsInForceAndKeepsEveryOtherKey() throws IOException {
        write("{ \"$Enabled\": false, \"Spring_Fair\": { \"Stats\": { \"Kites_Flown\": { \"Order\": 5 } } } }");

        assertTrue(AlmanacOwnerLayers.writeMenuTab(new Knobs(false, false)));

        JsonObject root = read();
        assertFalse(root.get("$Enabled").getAsBoolean());
        assertTrue(root.has("Spring_Fair"), "a season override survives");
        JsonObject group = root.getAsJsonObject(AlmanacOwnerLayers.MENU_TAB_KEY);
        assertFalse(group.get(AlmanacOwnerLayers.SHOW_KEY).getAsBoolean());
        assertFalse(group.get(AlmanacOwnerLayers.ONLY_WHILE_LIVE_KEY).getAsBoolean());
        assertEquals(new Knobs(false, false), AlmanacMenuTab.knobs(), "in force without a re-read");
        assertTrue(AlmanacOwnerLayers.menuTabWritten());
    }
}
