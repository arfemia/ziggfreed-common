package com.ziggfreed.common.commerce.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every line this family says has to have somewhere to resolve from.
 *
 * <p>A missing key is the failure mode a command surface cannot have: nothing throws, nothing logs,
 * and a player is shown the raw key instead of a sentence - which nobody notices until somebody who
 * does not speak English reports it. Rendering a real one needs a booted localization module, so this
 * asks the question that CAN be answered without a server: does the shipped English file carry every
 * key the sources ask for, and does the family's command line say what the command it drives is
 * called.
 *
 * <p>It reads SOURCES rather than calling anything, deliberately: a key handed to a message helper is
 * a literal in the file, and discovering them by walking the package means a verb added later is
 * covered without anybody remembering to list it here.
 */
class CommerceAdminKeysTest {

    /** The command package, walked rather than listed. */
    private static final Path SOURCE_DIR = Path.of("src", "main", "java", "com", "ziggfreed",
            "common", "commerce", "command");

    /** The shipped English file. Its NAME is the key prefix every entry inside it resolves under. */
    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages",
            "en-US", "ziggfreedcommon.commerce.admin.lang");

    /** The two files that hold the prefix and the command names themselves, not keys. */
    private static final Set<String> NOT_SCANNED =
            Set.of("CommerceAdminMessages.java", "CommerceCommandLine.java");

    /** A key as it is written in a source file: dotted, lower case, no spaces. */
    private static final Pattern DOTTED_LITERAL =
            Pattern.compile("\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\"");

    /** What a description key is built from, which is a DIFFERENT key than the literal it names. */
    private static final Pattern DESCRIPTION = Pattern.compile("desc\\(\"([^\"]+)\"\\)");

    @Test
    @DisplayName("every key the commands say is in the shipped English file")
    void everySpokenKeyExists() throws IOException {
        Set<String> shipped = shippedKeys();
        Set<String> descriptionArgs = new LinkedHashSet<>();
        Set<String> spoken = new TreeSet<>();

        for (Path source : sources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher descriptions = DESCRIPTION.matcher(text);
            while (descriptions.find()) {
                descriptionArgs.add(descriptions.group(1));
                spoken.add("desc." + descriptions.group(1));
            }
            Matcher literals = DOTTED_LITERAL.matcher(text);
            while (literals.find()) {
                spoken.add(literals.group(1));
            }
        }
        spoken.removeAll(descriptionArgs);

        assertTrue(spoken.size() > 20, "the scan found almost nothing, so it is not scanning");
        List<String> missing = new ArrayList<>();
        for (String key : spoken) {
            if (!shipped.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing, "keys the commands say with nothing to resolve them from");
    }

    @Test
    @DisplayName("every registered verb has a help line of its own")
    void everyVerbIsDescribed() throws IOException, IllegalAccessException {
        Set<String> shipped = shippedKeys();
        List<String> missing = new ArrayList<>();
        if (!shipped.contains("desc.family")) {
            missing.add("desc.family");
        }
        for (Field field : CommerceCommandLine.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String verb = (String) field.get(null);
            if (!CommerceCommandLine.FAMILY.equals(verb) && !shipped.contains("desc." + verb)) {
                missing.add("desc." + verb);
            }
        }
        assertEquals(List.of(), missing, "the engine resolves a command description as a KEY, so a "
                + "verb without one shows the reader raw text nobody translated");
    }

    @Test
    @DisplayName("the replayable line names the command that parses it")
    void theRetryLineNamesItsOwnCommand() {
        assertEquals("/zigcommerce give --player=Anne --currency=Bounty_Token --amount=300",
                CommerceCommandLine.give("Anne", "Bounty_Token", 300L));
    }

    @Test
    @DisplayName("the key prefix is the shipped file's own name")
    void thePrefixMatchesTheFileName() {
        String fileName = ENGLISH.getFileName().toString();
        assertEquals(fileName.substring(0, fileName.length() - ".lang".length()) + ".",
                CommerceAdminMessages.PREFIX,
                "a lang file's name IS the key prefix, so the two cannot be written independently");
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
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            int split = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || split <= 0) {
                continue;
            }
            keys.add(trimmed.substring(0, split).strip());
        }
        return keys;
    }
}
