package com.ziggfreed.common.encounter.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every line the {@code /zigencounter} family says has somewhere to resolve from: a missing key is
 * the failure a command surface cannot have (nothing throws, a reader is shown the raw key), so this
 * asks the question that CAN be answered without a server, by reading the sources and the shipped
 * English file. The same guard the flair and progression families carry, on the same terms.
 */
class EncounterAdminKeysTest {

    private static final Path SOURCE_DIR = Path.of("src", "main", "java", "com", "ziggfreed", "common", "encounter",
            "command");

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.encounter.lang");

    /** The in-file prefix the admin family's keys sit under. */
    private static final String ADMIN = "admin.";

    /** The files that hold the prefix and the command names themselves, not keys. */
    private static final Set<String> NOT_SCANNED = Set.of("EncounterAdminMessages.java", "EncounterCommandLine.java");

    private static final Pattern DOTTED_LITERAL = Pattern.compile("\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\"");

    private static final Pattern DESCRIPTION = Pattern.compile("desc\\(\"([^\"]+)\"\\)");

    private static final Pattern DESCRIPTION_OF_VERB = Pattern.compile("desc\\(EncounterCommandLine\\.([A-Z_]+)\\)");

    @Test
    void everySpokenKeyExistsInTheShippedEnglishFile() throws IOException {
        Set<String> shipped = shippedKeys();
        Set<String> spoken = new TreeSet<>();
        for (Path source : sources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher descriptions = DESCRIPTION.matcher(text);
            while (descriptions.find()) {
                spoken.add(ADMIN + "desc." + descriptions.group(1));
            }
            Matcher verbs = DESCRIPTION_OF_VERB.matcher(text);
            while (verbs.find()) {
                spoken.add(ADMIN + "desc." + verbOf(verbs.group(1)));
            }
            Matcher literals = DOTTED_LITERAL.matcher(text);
            while (literals.find()) {
                String literal = literals.group(1);
                if (!literal.startsWith("arg.")) {
                    spoken.add(ADMIN + literal);
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String key : spoken) {
            if (!shipped.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), () -> "keys the commands say but " + ENGLISH + " does not ship: " + missing);
    }

    @Test
    void everyVerbHasADescription() throws IOException {
        Set<String> shipped = shippedKeys();
        for (String verb : List.of("family", "list", "inspect", "spawn", "end", "state", "validate", "reload")) {
            assertTrue(shipped.contains(ADMIN + "desc." + verb), "no description for " + verb);
        }
    }

    private static String verbOf(String constant) {
        return constant.toLowerCase(Locale.ROOT);
    }

    private static Set<String> shippedKeys() throws IOException {
        assertTrue(Files.isRegularFile(ENGLISH), "missing " + ENGLISH.toAbsolutePath());
        Set<String> keys = new LinkedHashSet<>();
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

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.list(SOURCE_DIR)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !NOT_SCANNED.contains(f.getFileName().toString()))
                    .sorted().toList();
        }
    }
}
