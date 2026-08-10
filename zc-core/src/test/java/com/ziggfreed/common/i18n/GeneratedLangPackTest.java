package com.ziggfreed.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure coverage for {@link GeneratedLangPack}'s file-writing core + the filename-prefix key
 * convention. Registration ({@link GeneratedLangPack#registerZipPack}) needs a live
 * {@code AssetModule} and is engine-plumbing, untested here.
 */
class GeneratedLangPackTest {

    // --- storedKeyPrefix / storedKey: the M4 filename-minus-.lang prefix rule ---

    @Test
    void storedKeyPrefix_isFilenameMinusLangExtension() {
        assertEquals("server", GeneratedLangPack.storedKeyPrefix("server.lang"));
        assertEquals("client", GeneratedLangPack.storedKeyPrefix("client.lang"));
        // A filename WITHOUT the .lang extension is returned verbatim (defensive).
        assertEquals("server", GeneratedLangPack.storedKeyPrefix("server"));
    }

    @Test
    void storedKey_prependsThePrefixSoAServerLangEntryLandsUnderServer() {
        // items.X.description.zplain written into server.lang -> server.items.X.description.zplain,
        // the exact stored key the display-side lookup probes (the M4 fix's whole point).
        assertEquals("server.items.Weapon_Sword_Crude.description.zplain",
                GeneratedLangPack.storedKey("server.lang", "items.Weapon_Sword_Crude.description.zplain"));
    }

    // --- writeLocaleFiles: per-locale file shapes ---

    @Test
    void writeLocaleFiles_writesOneFilePerLocaleAtTheConventionPath(@TempDir Path root) throws IOException {
        GeneratedLangPack.writeLocaleFiles(root, "server.lang", perLocale(
                "en-US", entry("items.A.description.zplain", "A clean blade."),
                "de-DE", entry("items.A.description.zplain", "Eine saubere Klinge.")), null);

        Path en = root.resolve("Server").resolve("Languages").resolve("en-US").resolve("server.lang");
        Path de = root.resolve("Server").resolve("Languages").resolve("de-DE").resolve("server.lang");
        assertTrue(Files.isRegularFile(en));
        assertTrue(Files.isRegularFile(de));
        assertTrue(Files.readString(en, StandardCharsets.UTF_8)
                .contains("items.A.description.zplain = A clean blade."));
        assertTrue(Files.readString(de, StandardCharsets.UTF_8)
                .contains("items.A.description.zplain = Eine saubere Klinge."));
    }

    @Test
    void writeLocaleFiles_skipsEmptyValuedEntries(@TempDir Path root) throws IOException {
        GeneratedLangPack.writeLocaleFiles(root, "server.lang", perLocale(
                "en-US", entry("items.A.description.zplain", "", "items.B.description.zplain", "Kept.")), null);
        String content = Files.readString(
                root.resolve("Server").resolve("Languages").resolve("en-US").resolve("server.lang"),
                StandardCharsets.UTF_8);
        assertFalse(content.contains("items.A.description.zplain"), "empty value must be skipped");
        assertTrue(content.contains("items.B.description.zplain = Kept."));
    }

    @Test
    void writeLocaleFiles_isIdempotent_secondWriteReplacesWholesale(@TempDir Path root) throws IOException {
        Path file = root.resolve("Server").resolve("Languages").resolve("en-US").resolve("server.lang");

        GeneratedLangPack.writeLocaleFiles(root, "server.lang",
                perLocale("en-US", entry("items.A.description.zplain", "First.")), null);
        String first = Files.readString(file, StandardCharsets.UTF_8);

        // Same input a second time -> byte-identical file (idempotent boot regeneration).
        GeneratedLangPack.writeLocaleFiles(root, "server.lang",
                perLocale("en-US", entry("items.A.description.zplain", "First.")), null);
        assertEquals(first, Files.readString(file, StandardCharsets.UTF_8));

        // A CHANGED input replaces the file wholesale (no stale leftovers).
        GeneratedLangPack.writeLocaleFiles(root, "server.lang",
                perLocale("en-US", entry("items.B.description.zplain", "Second.")), null);
        String second = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(second.contains("items.A.description.zplain"), "stale entry must be gone");
        assertTrue(second.contains("items.B.description.zplain = Second."));
    }

    // --- escapeValue: matches the proven in-game escaper ---

    @Test
    void escapeValue_keepsBareMarkupButEscapesNewlinesAndTrimWrapsWhitespace() {
        // Inner double-quotes in a non-trim-wrapped value stay bare (a generated <color> row works).
        assertEquals("<color is=\"#FF0000\">Red</color>", GeneratedLangPack.escapeValue("<color is=\"#FF0000\">Red</color>"));
        assertEquals("a\\nb", GeneratedLangPack.escapeValue("a\nb"));
        // Leading whitespace forces quote-wrapping (its inner quotes then escaped).
        assertEquals("\" padded\"", GeneratedLangPack.escapeValue(" padded"));
    }

    // --- fixtures ---

    private static Map<String, String> entry(String... keyThenValue) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            m.put(keyThenValue[i], keyThenValue[i + 1]);
        }
        return m;
    }

    private static Map<String, Map<String, String>> perLocale(Object... localeThenEntries) {
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        for (int i = 0; i < localeThenEntries.length; i += 2) {
            @SuppressWarnings("unchecked")
            Map<String, String> entries = (Map<String, String>) localeThenEntries[i + 1];
            m.put((String) localeThenEntries[i], entries);
        }
        return m;
    }
}
