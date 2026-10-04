package com.ziggfreed.common.calendar.command;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;

/**
 * Every line /zigcalendar says has somewhere to resolve from in the shipped English admin file: a missing
 * key is the failure a command surface cannot have (nothing throws, a reader sees the raw key).
 */
class CalendarAdminKeysTest {

    private static final String FILE = "ziggfreedcommon.calendar.admin.lang";

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "calendar", "command");

    /** The files that hold the prefix and the command names, not keys. */
    private static final Set<String> NOT_SCANNED = Set.of("CalendarAdminMessages.java", "CalendarCommandLine.java");

    private static final Pattern DOTTED_LITERAL = Pattern.compile("\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\"");

    /** A description literal, {@code desc("arg.event")}: it resolves under {@code desc.}, never bare. */
    private static final Pattern DESCRIPTION = Pattern.compile("desc\\(\"([^\"]+)\"\\)");

    /** Keys a command composes rather than spells out (each verb's help line and the force answers). */
    private static final List<String> COMPOSED = List.of("desc.family", "desc.list", "desc.status", "desc.force",
            "desc.force.on", "desc.force.off", "desc.force.clear", "desc.reload", "desc.arg.event",
            "force.on.done", "force.off.done", "force.clear.done");

    @Test
    void everyKeyTheCommandsSpellOutShipsInEnglish() throws IOException {
        Set<String> english = CalendarFixtures.englishKeys(FILE);
        Set<String> said = new TreeSet<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !NOT_SCANNED.contains(p.getFileName().toString())).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher description = DESCRIPTION.matcher(source);
                while (description.find()) {
                    said.add("desc." + description.group(1));
                }
                Matcher literal = DOTTED_LITERAL.matcher(DESCRIPTION.matcher(source).replaceAll(""));
                while (literal.find()) {
                    said.add(literal.group(1));
                }
            }
        }
        assertFalse(said.isEmpty(), "the scan found the command keys");
        for (String key : said) {
            assertTrue(english.contains(key), "en-US " + FILE + " does not ship " + key);
        }
    }

    @Test
    void everyComposedKeyShipsInEnglish() {
        Set<String> english = CalendarFixtures.englishKeys(FILE);
        for (String key : COMPOSED) {
            assertTrue(english.contains(key), "en-US " + FILE + " does not ship " + key);
        }
    }

    @Test
    void thePrefixIsTheShippedFileName() {
        assertEquals(FILE.substring(0, FILE.length() - ".lang".length()) + ".", CalendarAdminMessages.PREFIX);
    }
}
