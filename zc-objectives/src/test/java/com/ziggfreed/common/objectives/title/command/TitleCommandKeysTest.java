package com.ziggfreed.common.objectives.title.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every line the {@code /zigtitle} family says has to resolve from the shipped English command file,
 * and every verb and argument needs a help line: a missing key throws nothing and shows the reader
 * the raw key, so this reads the sources and the file, the flair family's guard on the same terms.
 */
class TitleCommandKeysTest {

    private static final Path SOURCE_DIR = Path.of("src", "main", "java", "com", "ziggfreed", "common",
            "objectives", "title", "command");

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.title.command.lang");

    /** The files that hold the prefix and the command names themselves, not keys. */
    private static final Set<String> NOT_SCANNED = Set.of("TitleCommandMessages.java", "TitleCommandLine.java");

    private static final Pattern DOTTED_LITERAL = Pattern.compile("\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\"");

    private static final Pattern DESCRIPTION = Pattern.compile("desc\\(\"([^\"]+)\"\\)");

    private static final Pattern DESCRIPTION_OF_VERB = Pattern.compile("desc\\(verb\\)");

    @Test
    void everySpokenKeyExists() throws IOException {
        Set<String> shipped = shippedKeys();
        Set<String> descriptionArgs = new LinkedHashSet<>();
        Set<String> spoken = new TreeSet<>();
        boolean verbsDescribedByName = false;

        for (Path source : sources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher descriptions = DESCRIPTION.matcher(text);
            while (descriptions.find()) {
                descriptionArgs.add(descriptions.group(1));
                spoken.add("desc." + descriptions.group(1));
            }
            verbsDescribedByName |= DESCRIPTION_OF_VERB.matcher(text).find();
            Matcher literals = DOTTED_LITERAL.matcher(text);
            while (literals.find()) {
                spoken.add(literals.group(1));
            }
        }
        spoken.removeAll(descriptionArgs);
        assertTrue(verbsDescribedByName, "the verbs are described through desc(verb), checked below");
        assertTrue(spoken.size() > 8, "the scan found almost nothing, so it is not scanning");

        List<String> missing = new ArrayList<>();
        for (String key : spoken) {
            if (!shipped.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing, "keys the commands say with nothing to resolve them from");
    }

    @Test
    void theFamilyEveryVerbEveryArgumentAndTheSharedRefusalsAreShipped() throws IOException {
        Set<String> shipped = shippedKeys();
        List<String> missing = new ArrayList<>();
        for (String key : List.of("desc.family", "desc." + TitleCommandLine.GRANT,
                "desc." + TitleCommandLine.REVOKE, "desc." + TitleCommandLine.LIST,
                "desc.arg." + TitleCommandLine.ARG_PLAYER, "desc.arg." + TitleCommandLine.ARG_TITLE,
                // The shared target-player walk refuses with these two under the family's own prefix.
                "player.needed", "player.offline")) {
            if (!shipped.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing, "the engine resolves a command description as a KEY");
    }

    @Test
    void thePrefixIsTheFileName() {
        String fileName = ENGLISH.getFileName().toString();
        assertEquals(fileName.substring(0, fileName.length() - ".lang".length()) + ".", TitleCommandMessages.PREFIX);
    }

    private static List<Path> sources() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_DIR), "missing " + SOURCE_DIR.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(SOURCE_DIR)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !NOT_SCANNED.contains(p.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    private static Set<String> shippedKeys() throws IOException {
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
