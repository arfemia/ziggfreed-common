package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * Every key the module's code speaks, and every key one of its shipped files names, is authored in the
 * English file, written without the file's own prefix.
 */
class ReputationLangTest {

    static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.reputation.lang");

    private static final Path SHIPPED = Path.of("src", "main", "resources", "Server");

    private static final Pattern OWN_KEY = Pattern.compile("\"(ziggfreedcommon\\.reputation\\.[A-Za-z0-9_.]+)\"");

    @Test
    void everyKeyTheCodeSpeaksIsAuthoredInEnglish() throws IOException {
        Set<String> english = englishKeys();
        List<String> missing = new ArrayList<>();
        for (String key : ReputationText.SPOKEN) {
            if (!english.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "not in " + ENGLISH + ": " + missing);
    }

    @Test
    void everyKeyAShippedFileNamesIsAuthoredInEnglish() throws IOException {
        Set<String> english = englishKeys();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SHIPPED)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                Matcher matcher = OWN_KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String key = matcher.group(1).substring(ReputationText.PREFIX.length());
                    if (!english.contains(key)) {
                        missing.add(file.getFileName() + ": " + key);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "not in " + ENGLISH + ": " + missing);
    }

    /** The keys the English file authors. */
    @Nonnull
    static Set<String> englishKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String raw : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (!line.isEmpty() && !line.startsWith("#") && eq > 0) {
                keys.add(line.substring(0, eq).trim());
            }
        }
        return keys;
    }
}
