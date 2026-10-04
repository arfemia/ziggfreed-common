package com.ziggfreed.common.dialogue.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A daily first-visit beat keeps ONE key only if the page hands the family of its earlier windows
 * to every spend. The page cannot be built in a unit JVM, so the guard reads its source, the way
 * {@code DialoguePageRenderSubjectTest} does.
 */
class DialoguePageOnceFamilyTest {

    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common",
            "dialogue", "page", "DialoguePage.java");

    @Test
    void theEntryWindowFamilyTravelsWithItsKeyToEverySpend() throws IOException {
        String source = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertTrue(source.contains("pendingEntryOnceFamily = entry.onceFamily();"),
                "the page keeps the family the open resolved beside the pending key");
        Matcher spends = Pattern.compile("consumeOnce\\(\\s*pendingEntryOnceKey\\s*,\\s*([A-Za-z]+)\\s*,")
                .matcher(source);
        int count = 0;
        while (spends.find()) {
            count++;
            assertEquals("pendingEntryOnceFamily", spends.group(1),
                    "every spend hands the family on, or a daily beat keeps a key for every day it was played");
        }
        assertEquals(2, count, "an option click and the implicit Farewell both spend the pending beat");
    }
}
