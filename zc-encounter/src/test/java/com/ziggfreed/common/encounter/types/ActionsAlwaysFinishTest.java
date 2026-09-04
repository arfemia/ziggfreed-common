package com.ziggfreed.common.encounter.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every action this library registers answers the engine FINISHED on every path: to the engine a
 * {@code false} from an action's {@code execute} means "still running", and a blocking action list
 * stays on that action, asking again every tick, until it says otherwise. An action with nothing
 * to do therefore says so in the log and answers true, or the script it sits in stalls where it
 * stands. The engine types cannot be stood up in a unit JVM, so the contract is pinned where it is
 * written: no registered action source carries a literal {@code return false;}.
 */
class ActionsAlwaysFinishTest {

    private static final Path TYPES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "encounter", "types");

    /** The registered action classes, by their file-name prefix; the sensors answer a match and are not held to this. */
    private static final String ACTION_PREFIX = "ActionZig";

    @Test
    void noRegisteredActionEverAnswersStillRunning() throws IOException {
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.list(TYPES)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith(ACTION_PREFIX)).sorted().toList()) {
                scanned++;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().equals("return false;")) {
                        hits.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertEquals(EncounterTypes.ALL.size() - 2, scanned, "the three registered actions were scanned");
        assertTrue(hits.isEmpty(), () -> "a registered action answers the engine 'still running' (" + hits.size()
                + " hit(s)); answer true and say in the log why nothing was done:\n" + String.join("\n", hits));
    }
}
