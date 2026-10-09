package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.protocol.ParamValue;
import com.hypixel.hytale.server.core.Message;

/**
 * A line as a player reads it in English: the shipped en-US {@code ziggfreedcommon.almanac.lang} filled the way the
 * client fills it, each nested key in place, numbers bare, a plural's option chosen. A key the file lacks, an argument
 * the client would not read and an unspaced plural option ({@code one{day}}, which the client renders as nothing) all
 * fail the test, so a test that reads a line back also proves it reaches the player whole.
 */
public final class AlmanacEnglish {

    private static Map<String, String> english;

    private AlmanacEnglish() {
    }

    /** The shipped en-US almanac file, key to value. */
    @Nonnull
    public static Map<String, String> file() {
        if (english == null) {
            Path file = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
                    "ziggfreedcommon.almanac.lang");
            Map<String, String> values = new LinkedHashMap<>();
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    int eq = trimmed.indexOf('=');
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && eq > 0) {
                        values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            english = values;
        }
        return english;
    }

    /** {@code message} as the en-US file reads it: each nested key in place, numbers bare, plurals chosen. */
    @Nonnull
    public static String english(@Nullable Message message) {
        assertNotNull(message, "a line to read");
        return render(message.getFormattedMessage());
    }

    @Nonnull
    private static String render(@Nonnull FormattedMessage message) {
        if (message.messageId == null) {
            return message.rawText == null ? "" : message.rawText;
        }
        assertTrue(message.messageId.startsWith(AlmanacText.PREFIX), message.messageId);
        String key = message.messageId.substring(AlmanacText.PREFIX.length());
        String value = file().get(key);
        assertNotNull(value, "en-US ziggfreedcommon.almanac.lang ships no " + key);
        return fill(value, message);
    }

    @Nonnull
    private static String fill(@Nonnull String text, @Nonnull FormattedMessage message) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = closing(text, i);
            out.append(argument(text.substring(i + 1, close), message));
            i = close + 1;
        }
        return out.toString();
    }

    @Nonnull
    private static String argument(@Nonnull String body, @Nonnull FormattedMessage message) {
        String[] parts = body.split(",", 3);
        String name = parts[0].trim();
        if (parts.length == 1) {
            FormattedMessage nested = message.messageParams == null ? null : message.messageParams.get(name);
            return nested != null ? render(nested) : Long.toString(number(message, name));
        }
        String type = parts[1].trim();
        if (type.equals("number")) {
            return Long.toString(number(message, name));
        }
        if (type.equals("plural") && parts.length == 3) {
            return plural(parts[2].trim(), number(message, name), message);
        }
        return fail("an argument the client would not read: {" + body + "}");
    }

    /** {@code one {...} other {...}}: keyword, ONE space, brace, as the client reads a plural option. */
    @Nonnull
    private static String plural(@Nonnull String options, long count, @Nonnull FormattedMessage message) {
        Map<String, String> bodies = new HashMap<>();
        int i = 0;
        while (i < options.length()) {
            int space = options.indexOf(' ', i);
            assertTrue(space > i, "a plural option is keyword, one space, brace: " + options);
            assertEquals('{', options.charAt(space + 1), "a plural option is keyword, one space, brace: " + options);
            int close = closing(options, space + 1);
            bodies.put(options.substring(i, space), options.substring(space + 2, close));
            i = close + 1;
            while (i < options.length() && options.charAt(i) == ' ') {
                i++;
            }
        }
        String chosen = count == 1 && bodies.containsKey("one") ? bodies.get("one") : bodies.get("other");
        assertNotNull(chosen, "a plural names its other: " + options);
        return fill(chosen, message);
    }

    private static int closing(@Nonnull String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                depth++;
            } else if (text.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }
        return fail("an unclosed brace in " + text);
    }

    private static long number(@Nonnull FormattedMessage message, @Nonnull String name) {
        ParamValue value = message.params == null ? null : message.params.get(name);
        if (value instanceof LongParamValue typed) {
            return typed.value;
        }
        if (value instanceof IntParamValue typed) {
            return typed.value;
        }
        return fail("no number bound to {" + name + "} of " + message.messageId);
    }
}
