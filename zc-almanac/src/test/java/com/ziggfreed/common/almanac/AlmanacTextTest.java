package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every line the Almanac says has somewhere to resolve from in English: a missing key is the failure
 * a page cannot show (nothing throws, the reader sees the raw key). The other eight locales arrive in
 * the lang wave.
 */
class AlmanacTextTest {

    private static final Path EN_US = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.almanac.lang");

    @Test
    void everySpokenKeyShipsInEnglish() throws IOException {
        Set<String> english = keys(EN_US);
        for (String key : AlmanacText.SPOKEN) {
            assertTrue(english.contains(key), "en-US ziggfreedcommon.almanac.lang does not ship " + key);
        }
    }

    @Test
    void thePrefixIsTheShippedFileName() {
        String file = EN_US.getFileName().toString();
        assertEquals(file.substring(0, file.length() - ".lang".length()) + ".", AlmanacText.PREFIX);
    }

    @Test
    void theRowBadgeIsRetiredInEveryLocale() throws IOException {
        Path languages = EN_US.getParent().getParent();
        List<Path> files;
        try (Stream<Path> dirs = Files.list(languages)) {
            files = dirs.map(dir -> dir.resolve(EN_US.getFileName())).filter(Files::isRegularFile).toList();
        }
        assertEquals(9, files.size(), "the Almanac ships in nine locales");
        for (Path file : files) {
            assertFalse(keys(file).contains("badge.live"), "the hero's chip replaced the row badge: " + file);
        }
        assertFalse(AlmanacText.SPOKEN.contains("badge.live"));
    }

    /** U+2014, spelled as a code point so this file carries none itself. */
    private static final char EM_DASH = (char) 0x2014;

    @Test
    void noEnglishLineCarriesAnEmDash() throws IOException {
        for (String line : Files.readAllLines(EN_US, StandardCharsets.UTF_8)) {
            assertFalse(line.indexOf(EM_DASH) >= 0, "an em-dash in player text: " + line);
        }
    }

    private static Set<String> keys(Path file) throws IOException {
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
