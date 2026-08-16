package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * What an author wrote into a key's numbered slots has to reach the line the player reads.
 *
 * <p>A whole ladder of content is usually ONE translated line with each rung supplying its own
 * number, which is what {@code TextArgs} is for. A source that resolves the key and drops the args
 * renders that line with an empty slot where the number belonged, on every rung at once - and the
 * content file it came from still reads as perfectly correct, so the search starts in the wrong
 * place. Both content types are pinned here, because a shared group whose two carriers drift is
 * worse than no shared group.
 *
 * <p><b>Where the guard points, and why there.</b> Naming content is a TEXT SOURCE's job now, so
 * this reads the library's own source rather than the page: a surface walking the merged catalogue
 * has no catalogue of its own to read args from, and the one place that still does is the fold that
 * kept them.
 *
 * <p><b>Why this is a source check.</b> Reaching the rendering would initialize a page whose engine
 * base class builds a logger in a static initializer that refuses to load in a JVM whose log manager
 * is already up, which a test JVM's is. There is no seam between "which args" and the message they
 * are handed to. The library's dialogue page render is pinned the same way, for the same reason. The
 * expansion itself is behaviour and is pinned where it lives, beside the shared schema.
 */
class ObjectiveBookTextArgsTest {

    private static final Path TEXT_SOURCE = Path.of("src", "main", "java", "com", "ziggfreed",
            "common", "objectives", "runtime", "ProgressionDefaults.java");

    @Test
    void bothContentKindsHandTheirAuthoredArgsToTheKey() throws IOException {
        String title = squashed(body("public Message title("));
        assertTrue(title.contains("quest.titleArgs()")
                        && title.contains("earned.titleArgs()"),
                "title() must pass the authored title args for BOTH content kinds: a title key"
                        + " resolved without them renders its numbered slots empty for every rung"
                        + " of a ladder");

        String flavor = squashed(body("public Message flavor("));
        assertTrue(flavor.contains("quest.flavorArgs()")
                        && flavor.contains("earned.flavorArgs()"),
                "flavor() must pass the authored flavor args, which is where a ladder's number"
                        + " usually lives");
    }

    @Test
    void theKeyReaderHandsThemToTheMessageRatherThanDroppingThem() throws IOException {
        assertTrue(squashed(body("private static Message key(")).contains(
                        "ContentKeys.tr(localizationKey,args(authoredArgs,amount))"),
                "the one key reader must bind the args it was given to the key it resolves, and it"
                        + " must resolve that key through the authored-key seam: a key handed over"
                        + " with no namespace is one the client cannot resolve, so the player reads"
                        + " the key itself");
    }

    @Test
    void theAmountSentinelIsAnsweredWithARawNumber() throws IOException {
        String body = squashed(body("private static Object[] args("));

        assertTrue(body.contains("ContentTextAsset.ARG_AMOUNT"),
                "the amount sentinel is the one the shared schema names, so it is matched by that"
                        + " constant rather than by a spelling of it kept here");
        assertTrue(body.contains("NumberFormatter.grouped(amount)"),
                "the value of @amount is this surface's own rendering decision, and here it is the"
                        + " number the content asks for, grouped for readability");
        assertFalse(body.contains("Msg."),
                "a digit needs no translating: it goes in as a raw value, not as a message the"
                        + " client would be asked to resolve");
    }

    /** One method's body, brace-matched from the start of its signature. */
    @Nonnull
    private static String body(@Nonnull String signature) throws IOException {
        String source = Files.readString(TEXT_SOURCE, StandardCharsets.UTF_8);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " not found in " + TEXT_SOURCE.toAbsolutePath()
                + " - the text source was reshaped, so revisit this guard");
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, signature + " has no body");
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
        throw new IllegalStateException("unbalanced braces reading " + signature);
    }

    /** Whitespace removed, so a reflowed call site still reads as the same call. */
    @Nonnull
    private static String squashed(@Nonnull String body) {
        return body.replaceAll("\\s+", "");
    }
}
