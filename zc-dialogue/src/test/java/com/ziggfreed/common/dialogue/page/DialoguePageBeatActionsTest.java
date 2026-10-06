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
 * A beat's Actions run at exactly the two moments its claim is spent (a chosen line, which includes a
 * line an extension added, and the Farewell row), before the spend and before the chosen line's own
 * actions; Escape runs neither. The page cannot be built in a unit JVM, so the guard reads its source, the
 * way {@code DialoguePageOnceFamilyTest} does.
 */
class DialoguePageBeatActionsTest {

    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common",
            "dialogue", "page", "DialoguePage.java");

    private static final String RUN = "runBeatActions(pendingEntryOnceKey, pendingEntryActions";

    @Test
    void theBeatsActionsRunAtBothSpendsFirst() throws IOException {
        String source = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertTrue(source.contains("pendingEntryActions = entry.actions();"),
                "the page keeps the actions the open resolved beside the pending key");

        int click = source.indexOf("public void handleDataEvent(");
        int run = source.indexOf(RUN, click);
        int line = source.indexOf("execute(option.getActions()", click);
        int spend = source.indexOf("consumeOnce(pendingEntryOnceKey", click);
        assertTrue(click > 0 && run > click && run < line && line < spend,
                "on a chosen line the beat's actions run first, then the line's, then the spend");

        int farewell = source.indexOf("private void consumeFarewell(");
        int farewellRun = source.indexOf(RUN, farewell);
        int farewellSpend = source.indexOf("consumeOnce(pendingEntryOnceKey", farewell);
        assertTrue(farewell > 0 && farewellRun > farewell && farewellRun < farewellSpend,
                "on Farewell the beat's actions run before the spend");

        Matcher runs = Pattern.compile(Pattern.quote(RUN)).matcher(source);
        int count = 0;
        while (runs.find()) {
            count++;
        }
        assertEquals(2, count, "two spend points, two runs; nothing else runs them");
    }
}
