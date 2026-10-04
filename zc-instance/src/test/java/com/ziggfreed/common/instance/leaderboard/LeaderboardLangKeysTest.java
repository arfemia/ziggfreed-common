package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every line the boss-records screen says resolves from the module's own English file, and every one
 * of them but the footer is a bare translation: the page paints them on {@code .Text} sinks (a
 * {@code TextButton}, a header Label), which resolve a key but never substitute a parameter, so a
 * {@code {0}} there would print at players. Every line {@code /zigleaderboard} says, and every
 * description its help resolves, ships there too. Read from the sources and the shipped file; no server.
 */
class LeaderboardLangKeysTest {

    static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.leaderboard.lang");

    private static final Path MESSAGES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
            "leaderboard", "EncounterLeaderboardMessages.java");

    private static final Pattern LINE = Pattern.compile("line\\(\"([a-z][a-z0-9_.]*)\"");

    private static final Path COMMANDS = Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
            "leaderboard", "command");

    /** The files that hold the prefix and the command names themselves, not keys. */
    private static final Set<String> NOT_SCANNED = Set.of("LeaderboardCommandMessages.java", "LeaderboardCommandLine.java");

    private static final Pattern SAID = Pattern.compile("(?:refused|heading|detail)\\(ctx, \"([a-z][a-z0-9_.]*)\"");

    private static final Pattern DESCRIPTION = Pattern.compile("desc\\(\"([a-z][a-z0-9_.]*)\"\\)");

    private static final Pattern DESCRIPTION_OF_VERB = Pattern.compile("desc\\(LeaderboardCommandLine\\.([A-Z_]+)\\)");

    @Test
    void everyLineTheScreenSaysShipsInEnglish() throws IOException {
        Map<String, String> shipped = shipped();
        Set<String> said = new TreeSet<>();
        Matcher m = LINE.matcher(Files.readString(MESSAGES, StandardCharsets.UTF_8));
        while (m.find()) {
            said.add(m.group(1));
        }
        assertTrue(said.size() >= 20, "the scan found almost nothing, so it is not scanning: " + said);
        assertEquals(List.of(), said.stream().filter(key -> !shipped.containsKey(key)).toList(),
                "lines the screen says but " + ENGLISH + " does not ship");
    }

    @Test
    void everyScreenLineButTheFooterIsABareTranslation() throws IOException {
        for (Map.Entry<String, String> line : shipped().entrySet()) {
            if (line.getKey().equals("your_rank") || line.getKey().startsWith("command.")) {
                continue;
            }
            assertFalse(line.getValue().contains("{"), line.getKey()
                    + " is painted on a .Text sink, which never substitutes a parameter");
        }
    }

    @Test
    void everyLineTheCommandSaysShipsInEnglish() throws IOException {
        Map<String, String> shipped = shipped();
        Set<String> said = new TreeSet<>();
        try (Stream<Path> files = Files.list(COMMANDS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !NOT_SCANNED.contains(f.getFileName().toString())).sorted().toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                collect(SAID.matcher(text), "command.", said);
                collect(DESCRIPTION.matcher(text), "command.desc.", said);
                Matcher verbs = DESCRIPTION_OF_VERB.matcher(text);
                while (verbs.find()) {
                    said.add("command.desc." + verbs.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        assertTrue(said.size() >= 8, "the scan found almost nothing, so it is not scanning: " + said);
        assertEquals(List.of(), said.stream().filter(key -> !shipped.containsKey(key)).toList(),
                "lines the command says but " + ENGLISH + " does not ship");
    }

    @Test
    void everyVerbHasADescription() throws IOException {
        Map<String, String> shipped = shipped();
        for (String verb : List.of("family", "encounter")) {
            assertTrue(shipped.containsKey("command.desc." + verb), "no description for " + verb);
        }
    }

    /** {@code key -> value} for the shipped English file. */
    static Map<String, String> shipped() throws IOException {
        assertTrue(Files.isRegularFile(ENGLISH), "missing " + ENGLISH.toAbsolutePath());
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                continue;
            }
            out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return out;
    }

    private static void collect(Matcher m, String prefix, Set<String> into) {
        while (m.find()) {
            into.add(prefix + m.group(1));
        }
    }
}
