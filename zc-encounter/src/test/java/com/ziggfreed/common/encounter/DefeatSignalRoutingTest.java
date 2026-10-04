package com.ziggfreed.common.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The wiring half of the leash rule, which a unit JVM cannot run: a script's {@code zc:defeated}
 * beat reaches the payout only through the verdict ({@code EncounterLifecycle.defeatSignalled}), and
 * the one caller allowed to settle a defeat directly is the death system, which acts the instant a
 * death lands. A second direct caller would be a second way to pay for a boss nobody killed.
 */
class DefeatSignalRoutingTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "encounter");

    private static final Pattern DIRECT_DEFEAT = Pattern.compile("EncounterLifecycle\\.defeat\\(");

    @Test
    void theDefeatBeatGoesThroughTheVerdict() throws IOException {
        String signal = Files.readString(SOURCES.resolve(Path.of("signal", "EncounterSignalSystem.java")),
                StandardCharsets.UTF_8);
        assertTrue(signal.contains("case DEFEATED -> EncounterLifecycle.defeatSignalled("),
                "the signal system hands a zc:defeated beat to the verdict, never straight to the payout");
    }

    @Test
    void onlyTheDeathSystemSettlesADefeatDirectly() throws IOException {
        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                if (DIRECT_DEFEAT.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    callers.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("EncounterDeathSystem.java"), callers,
                "a direct defeat is the death system's alone: it settles the instant a death lands");
    }
}
