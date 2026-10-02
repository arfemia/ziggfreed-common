package com.ziggfreed.common.loot.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Every line the {@code /zigloot} family says has somewhere to resolve from, in every language the
 * library ships: a missing key is the failure a command surface cannot have (nothing throws, a
 * reader is shown the raw key), so this asks the question that can be answered without a server.
 */
class LootAdminKeysTest {

    private static final Path LANGUAGES = Path.of("src", "main", "resources", "Server", "Languages");

    private static final String FILE = "ziggfreedcommon.loot.admin.lang";

    private static final List<String> LOCALES = List.of("en-US", "de-DE", "es-ES", "fr-FR", "hu-HU", "it-IT",
            "pt-BR", "ru-RU", "tr-TR");

    /** What the family's commands and the shared findings reply resolve under this family's prefix. */
    private static final List<String> SPOKEN = List.of("desc.family", "desc." + LootCommandLine.VALIDATE,
            "validate.clean", "validate.counts", "finding", "more");

    @Test
    void everySpokenKeyShipsInEnglish() throws IOException {
        Set<String> english = keys("en-US");
        for (String key : SPOKEN) {
            assertTrue(english.contains(key), "en-US " + FILE + " does not ship " + key);
        }
    }

    @Test
    void everyLocaleShipsTheSameKeysAsEnglish() throws IOException {
        Set<String> english = keys("en-US");
        for (String locale : LOCALES) {
            assertEquals(english, keys(locale), locale + " " + FILE + " drifts from en-US");
        }
    }

    @Test
    void thePrefixIsTheShippedFileName() {
        assertEquals(FILE.substring(0, FILE.length() - ".lang".length()) + ".", LootAdminMessages.PREFIX);
    }

    private static Set<String> keys(String locale) throws IOException {
        Path file = LANGUAGES.resolve(locale).resolve(FILE);
        assertTrue(Files.isRegularFile(file), "missing " + file.toAbsolutePath());
        Set<String> keys = new TreeSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                continue;
            }
            keys.add(trimmed.substring(0, eq).trim());
        }
        return keys;
    }
}
