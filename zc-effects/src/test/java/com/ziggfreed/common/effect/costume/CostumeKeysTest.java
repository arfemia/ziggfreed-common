package com.ziggfreed.common.effect.costume;

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
 * Every line the costume says has an English key to resolve from: a missing key shows a reader the
 * raw key and throws nowhere. Other locales come in the lang wave and fall back to English per key.
 */
class CostumeKeysTest {

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.costume.lang");

    /** Every key the costume's notice and command speak, without the file's prefix. */
    private static final List<String> SPOKEN = List.of("desc.family", "desc." + CostumeCommand.OFF,
            "notice.dressed", "notice.dressed_by", "off.done", "off.none", "off.needs_player");

    @Test
    void everySpokenKeyShipsInEnglish() throws IOException {
        Set<String> english = keys();
        for (String key : SPOKEN) {
            assertTrue(english.contains(key), "en-US ziggfreedcommon.costume.lang does not ship " + key);
        }
    }

    @Test
    void thePrefixIsTheShippedFileName() {
        assertEquals(CostumeMessages.PREFIX + "lang", ENGLISH.getFileName().toString());
    }

    @Test
    void aPlayerWhoDressedYouIsNamedAndAnyoneElseIsNot() {
        assertEquals("notice.dressed_by", CostumeMessages.dressedKey("Ann"));
        assertEquals("notice.dressed", CostumeMessages.dressedKey(null));
        assertEquals("notice.dressed", CostumeMessages.dressedKey("  "));
    }

    @Test
    void theOffVerbIsDescribedUnderThisFamily() {
        assertEquals("zigcostume", CostumeCommand.FAMILY);
        assertEquals(CostumeMessages.PREFIX + "desc.off", CostumeMessages.desc(CostumeCommand.OFF));
    }

    private static Set<String> keys() throws IOException {
        assertTrue(Files.isRegularFile(ENGLISH), "missing " + ENGLISH.toAbsolutePath());
        Set<String> keys = new TreeSet<>();
        for (String line : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
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
