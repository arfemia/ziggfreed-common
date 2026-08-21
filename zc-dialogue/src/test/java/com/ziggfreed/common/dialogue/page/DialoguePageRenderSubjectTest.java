package com.ziggfreed.common.dialogue.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The one rule a dialogue render must never lose: it reads the PLAYER, not whoever opened the page.
 *
 * <p>The ref a page's {@code build} is handed is simply what the opener passed the page manager, and
 * every NPC-action route passes the NPC (the first-party barter-shop action does the same, and its
 * page compensates the same way this one does). Read player state off that ref instead and the first
 * screen is evaluated against an entity holding none of the player's components: quest-aware lines
 * read as not started, factor gates fail closed, and the missing options appear only after a click
 * re-renders through the player's own ref. The symptom - "the option only shows up after you click
 * something once" - is the kind nobody reports as a bug.
 *
 * <p><b>Why this is a source check.</b> Exercising it would need a live entity store, a placed NPC
 * and a connected player, none of which exist in a unit JVM; there is no seam between "which ref"
 * and "the engine call that reads it" to test in between. So the guard reads the render's own source
 * and fails if the resolution is dropped or if the opener's ref is threaded back into the evaluation
 * context. A change that legitimately reshapes the render should keep both properties true.
 */
class DialoguePageRenderSubjectTest {

    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common",
            "dialogue", "page", "DialoguePage.java");

    @Test
    void theRenderResolvesThePlayerFromThePagesOwnPlayerRef() throws IOException {
        String build = buildBody();
        assertTrue(build.contains("playerRef.getReference()"),
                "DialoguePage.build must resolve the player's own entity from the PlayerRef it holds;"
                        + " the ref argument is whoever opened the page, which for every NPC action is the NPC");
    }

    @Test
    void theEvaluationContextIsNotBuiltOnTheRefTheOpenerHandedIn() throws IOException {
        String build = buildBody();
        Matcher call = Pattern.compile("\\bcontext\\(([^;]*)\\);", Pattern.DOTALL).matcher(build);
        assertTrue(call.find(), "DialoguePage.build no longer builds an exec context - revisit this guard");
        String arguments = call.group(1);
        assertFalse(Pattern.compile("[,(]\\s*ref\\s*,").matcher(arguments).find(),
                "the render's exec context was handed the opener's ref: every condition on the first"
                        + " screen would then be asked about the NPC instead of the player");
    }

    /** The body of {@code build}, brace-matched from its signature. */
    private static String buildBody() throws IOException {
        String source = Files.readString(PAGE, StandardCharsets.UTF_8);
        int signature = source.indexOf("public void build(");
        assertTrue(signature >= 0, "DialoguePage.build not found at " + PAGE.toAbsolutePath());
        int open = source.indexOf('{', signature);
        assertTrue(open >= 0, "DialoguePage.build has no body");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces reading DialoguePage.build");
    }
}
