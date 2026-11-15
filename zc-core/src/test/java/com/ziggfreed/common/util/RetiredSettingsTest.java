package com.ziggfreed.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The update-day pass over an owner folder: a listed setting comes out of the file, wherever a
 * {@code *} path reaches it and whatever its case, every other key stays, and a copy of the file as it
 * was sits beside it; the second run finds nothing, so it changes nothing and reports nothing. Pure
 * file work, so it runs in the plain {@code test} JVM.
 */
class RetiredSettingsTest {

    private static final String RELEASE = "9.9.9";

    private static final List<RetiredSettings.Entry> ENTRIES = List.of(
            new RetiredSettings.Entry("settings.json", "crits", "The crit word moved."),
            new RetiredSettings.Entry("settings.json", "critStyle", "The crit word moved."),
            new RetiredSettings.Entry("settings.json", "overrides.*.pools.*.delay", "Commands run at once."),
            new RetiredSettings.Entry("missing.json", "anything", "Never read."));

    private static final String OWNER_FILE = "{\n"
            + "  \"enabled\": true,\n"
            + "  \"Crits\": false,\n"
            + "  \"critStyle\": \"#FF3B3B\",\n"
            + "  \"levelUpStyle\": \"#FFD700\",\n"
            + "  \"chance\": 0.25,\n"
            + "  \"overrides\": {\n"
            + "    \"$Comment\": {\"pools\": [{\"delay\": 1}]},\n"
            + "    \"Trork_\": {\"pools\": [{\"commands\": [\"say a\"], \"delay\": 40}, {\"delay\": 0}]},\n"
            + "    \"Goblin_\": {\"pools\": [{\"commands\": [\"say b\"]}]}\n"
            + "  }\n"
            + "}\n";

    @TempDir
    Path dir;

    @Test
    void theListedSettingsComeOutAndACopyOfTheFileStaysBesideIt() throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, OWNER_FILE, StandardCharsets.UTF_8);

        List<RetiredSettings.Outcome> outcomes = RetiredSettings.strip(dir, RELEASE, ENTRIES);

        assertEquals(1, outcomes.size(), "one outcome for the one file that wrote a retired setting");
        RetiredSettings.Outcome outcome = outcomes.get(0);
        assertFalse(outcome.failed());
        assertEquals(List.of("crits", "critStyle", "overrides.*.pools.*.delay (2)"), outcome.removed());
        assertEquals(List.of("The crit word moved.", "Commands run at once."), outcome.notes(),
                "each note once, in list order");

        Path copy = dir.resolve("settings.json.pre-" + RELEASE);
        assertEquals(copy, outcome.copy());
        assertEquals(OWNER_FILE, Files.readString(copy, StandardCharsets.UTF_8), "the copy is the file as it was");

        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(root.has("Crits"), "a retired key comes out whatever its case");
        assertFalse(root.has("critStyle"));
        assertTrue(root.get("enabled").getAsBoolean(), "an unlisted setting stays");
        assertEquals("#FFD700", root.get("levelUpStyle").getAsString());
        assertEquals("0.25", root.get("chance").getAsString(), "a number keeps its spelling");
        JsonObject overrides = root.getAsJsonObject("overrides");
        assertTrue(overrides.getAsJsonObject("$Comment").getAsJsonArray("pools").get(0).getAsJsonObject()
                .has("delay"), "a $ key is never an entry a * reaches");
        JsonObject firstPool = overrides.getAsJsonObject("Trork_").getAsJsonArray("pools").get(0).getAsJsonObject();
        assertFalse(firstPool.has("delay"), "a * path reaches into every pool");
        assertEquals("say a", firstPool.getAsJsonArray("commands").get(0).getAsString());
        assertTrue(outcome.line().contains("settings.json.pre-" + RELEASE), "the log line names the copy");
    }

    @Test
    void theSecondRunChangesNothingAndReportsNothing() throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, OWNER_FILE, StandardCharsets.UTF_8);
        RetiredSettings.strip(dir, RELEASE, ENTRIES);
        String afterFirst = Files.readString(file, StandardCharsets.UTF_8);

        List<RetiredSettings.Outcome> second = RetiredSettings.strip(dir, RELEASE, ENTRIES);

        assertTrue(second.isEmpty(), "nothing left to take out, so nothing to report");
        assertEquals(afterFirst, Files.readString(file, StandardCharsets.UTF_8), "the file is not rewritten");
        assertFalse(Files.exists(dir.resolve("settings.json.pre-" + RELEASE + ".2")), "no second copy");
    }

    @Test
    void aFileItCannotReadIsLeftAsItWas() throws IOException {
        Path file = dir.resolve("settings.json");
        String broken = "{ \"crits\": true, ";
        Files.writeString(file, broken, StandardCharsets.UTF_8);

        assertTrue(RetiredSettings.strip(dir, RELEASE, ENTRIES).isEmpty());
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("settings.json.pre-" + RELEASE)));
    }
}
