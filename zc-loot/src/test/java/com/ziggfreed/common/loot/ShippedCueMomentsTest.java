package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

/**
 * The loot cue moments this module ships ({@code Rare_Find}): every line key they write is spelt out under this
 * module's lang namespace and carried by its en-US file, so a rare roll on a server running the library alone shows
 * its words, never a raw key. The moment's own codec lives in zc-presentation, out of this module's reach, so the
 * files are read as plain JSON and every {@code Key} in them is checked.
 */
class ShippedCueMomentsTest {

    private static final Path MOMENTS = Path.of("src", "main", "resources", "Server", "ZiggfreedCommon",
            "FeedbackMoments");

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.loot.lang");

    private static final String PREFIX = "ziggfreedcommon.loot.";

    @Test
    void everyShippedCueLineReadsAKeyTheLootLangFileCarries() throws IOException {
        Set<String> english = englishKeys();
        List<String> problems = new ArrayList<>();
        int files = 0;
        try (Stream<Path> list = Files.list(MOMENTS)) {
            for (Path file : list.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                files++;
                List<String> keys = new ArrayList<>();
                keysOf(BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8)), keys);
                if (keys.isEmpty()) {
                    problems.add(file.getFileName() + ": no line names a Key");
                }
                for (String key : keys) {
                    if (!key.startsWith(PREFIX)) {
                        problems.add(file.getFileName() + ": '" + key + "' is not written out in full under " + PREFIX
                                + ", so it would be read through a consumer's catalogue");
                    } else if (!english.contains(key.substring(PREFIX.length()))) {
                        problems.add(file.getFileName() + ": '" + key + "' is not in " + ENGLISH);
                    }
                }
            }
        }
        assertTrue(files > 0, "no moment file under " + MOMENTS.toAbsolutePath());
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /** Every string value under a {@code Key} anywhere in {@code doc}, its comment left out. */
    private static void keysOf(BsonDocument doc, List<String> out) {
        for (var entry : doc.entrySet()) {
            if (entry.getKey().startsWith("$")) {
                continue;
            }
            BsonValue value = entry.getValue();
            if ("Key".equals(entry.getKey()) && value.isString()) {
                out.add(value.asString().getValue());
            } else if (value.isDocument()) {
                keysOf(value.asDocument(), out);
            } else if (value.isArray()) {
                for (BsonValue element : value.asArray()) {
                    if (element.isDocument()) {
                        keysOf(element.asDocument(), out);
                    }
                }
            }
        }
    }

    private static Set<String> englishKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String raw : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (!line.isEmpty() && !line.startsWith("#") && eq > 0) {
                keys.add(line.substring(0, eq).trim());
            }
        }
        return keys;
    }
}
