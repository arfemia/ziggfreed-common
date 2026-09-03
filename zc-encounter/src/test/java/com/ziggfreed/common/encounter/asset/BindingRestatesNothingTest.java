package com.ziggfreed.common.encounter.asset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The machine-checked statement that the SCRIPT owns the fight: no key of either encounter codec
 * names a phase, a threshold, a count, a sound, a role or a spawner. Those are what the engine's own
 * script vocabulary says (named States, a Stat filter, TriggerSpawners, PlaySound, ChangeTargetRole),
 * and a row restating any of them would be a second description of the fight that could disagree
 * with the first.
 *
 * <p>The scan is over the codec KEY names as written in the two asset sources; the state names an
 * author types as {@code Loot.OnPhase} map keys are data, not codec keys, and never reach it.
 */
class BindingRestatesNothingTest {

    private static final List<Path> SOURCES = List.of(
            Path.of("src", "main", "java", "com", "ziggfreed", "common", "encounter", "asset", "EncounterBindingAsset.java"),
            Path.of("src", "main", "java", "com", "ziggfreed", "common", "encounter", "asset",
                    "EncounterParticipationAsset.java"));

    private static final Pattern KEYED_CODEC = Pattern.compile("new KeyedCodec<>\\(\"([A-Za-z0-9]+)\"");

    /** The script's own vocabulary: a key WORD (one PascalCase segment, lower-cased) that restates the fight. */
    private static final Set<String> SCRIPT_WORDS = Set.of("threshold", "count", "sound", "role", "spawner",
            "spawnmarker", "marker", "music", "invulnerable", "fraction", "addcap", "throwable", "state");

    /** A phase NUMBERED as a word (Phase1, Phase2), the flat-prefix shape a script owns. */
    private static final Pattern NUMBERED_PHASE = Pattern.compile("(?i)^phase[0-9]+$");

    /** One PascalCase segment: an upper-case letter and whatever lower-case letters or digits follow it. */
    private static final Pattern WORD = Pattern.compile("[A-Z][a-z0-9]*");

    @Test
    void noCodecKeyNamesWhatTheScriptOwns() throws IOException {
        List<String> keys = keys();
        assertFalse(keys.isEmpty(), "no codec keys were found; the scan is broken");
        List<String> hits = new ArrayList<>();
        for (String key : keys) {
            if (restatesTheScript(key)) {
                hits.add(key);
            }
        }
        assertTrue(hits.isEmpty(), () -> "the binding row restates the script (" + hits.size()
                + " key(s)): " + String.join(", ", hits));
    }

    @Test
    void theKnobsTheScriptCannotSayAreAllThere() throws IOException {
        List<String> keys = keys();
        for (String expected : List.of("EncounterAsset", "Enabled", "Subject", "Participation", "Scale", "Timing",
                "Loot", "Leaderboard", "Progression", "Feedback", "Discovery", "TargetSlot", "MinShare",
                "HealthPerMember", "WipeGraceSeconds", "OnDefeat", "OnPhase", "QueueIfOffline", "Match", "Where",
                "DamageDealt", "DamageTaken", "Presence", "CreditDead", "CreditDisconnected")) {
            assertTrue(keys.contains(expected), "the codec lost the key " + expected);
        }
    }

    @Test
    void everyKeyIsPascalCase() throws IOException {
        for (String key : keys()) {
            assertTrue(Character.isUpperCase(key.charAt(0)), "codec key is not PascalCase: " + key);
        }
    }

    /** True when any word of {@code key} is the script's own vocabulary; MapMarker (the discovery marker) is not. */
    static boolean restatesTheScript(String key) {
        if ("MapMarker".equals(key) || "MarkerIcon".equals(key)) {
            return false;
        }
        Matcher words = WORD.matcher(key);
        while (words.find()) {
            String word = words.group().toLowerCase(Locale.ROOT);
            if (SCRIPT_WORDS.contains(word) || NUMBERED_PHASE.matcher(word).find()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theScanCatchesTheFlatPrefixedShapeAndSparesTheKeysThatOnlyLookLikeIt() {
        assertTrue(restatesTheScript("Phase1Role"));
        assertTrue(restatesTheScript("Phase2ThresholdFraction"));
        assertTrue(restatesTheScript("AddCount"));
        assertTrue(restatesTheScript("SpawnSoundId"));
        assertTrue(restatesTheScript("SpawnMarker"));
        assertFalse(restatesTheScript("EncounterAsset"));
        assertFalse(restatesTheScript("OnPhase"));
        assertFalse(restatesTheScript("PhaseChanged"));
        assertFalse(restatesTheScript("MapMarker"));
    }

    private static List<String> keys() throws IOException {
        List<String> keys = new ArrayList<>();
        for (Path source : SOURCES) {
            assertTrue(Files.isRegularFile(source), "missing source: " + source.toAbsolutePath());
            Matcher m = KEYED_CODEC.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (m.find()) {
                keys.add(m.group(1));
            }
        }
        return keys;
    }
}
