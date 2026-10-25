package com.ziggfreed.common.objectives.trainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * The trainer's service seam ships filled (M513): the library answers for the prestige, choosing again what a
 * prestige put aside and the suggested build, so a server running the library alone offers all three. And every
 * line the trainer page spells ships in its English file. Read from the registry and the sources; no server.
 */
class TrainerServicesTest {

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.trainer.lang");

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives",
            "trainer");

    private static final Pattern LINE = Pattern.compile("TrainerText\\.line\\(\"([a-z][a-z0-9_.]*)\"");

    @Test
    void theLibraryAnswersForEveryNamedService() {
        TrainerServices.registerBuiltIns();
        for (String id : List.of(TrainerServices.PRESTIGE, TrainerServices.RECHOOSE,
                TrainerServices.SUGGESTED_BUILD)) {
            assertTrue(TrainerServices.has(id), "nothing answers for " + id);
            RegistryLedger.RegistrationInfo info = TrainerServices.info().get(RegistryLedger.normalize(id));
            assertEquals(TrainerServices.LIBRARY, info.owner(), id + " is not the library's own");
        }
    }

    @Test
    void everyLineThePageSpellsShipsInEnglish() throws IOException {
        Set<String> said = new TreeSet<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                Matcher m = LINE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    said.add(m.group(1));
                }
            }
        }
        assertTrue(said.size() >= 30, "the scan found almost nothing, so it is not scanning: " + said);
        Set<String> shipped = new TreeSet<>();
        for (String line : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && eq > 0) {
                shipped.add(trimmed.substring(0, eq).trim());
            }
        }
        assertEquals(List.of(), said.stream().filter(key -> !shipped.contains(key)).toList(),
                "lines the trainer page says but " + ENGLISH + " does not ship");
    }
}
