package com.ziggfreed.common.track;

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

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.track.pick.PickService;

/**
 * Every line the track module says ships in its English file: each {@code TrackText.line} and {@code TreeText.line}
 * key its sources spell, and every sentence the one refusal and respec mapping ({@link TrackText#refusal},
 * {@link TrackText#respecLine}) answers for any status, the tree page and a trainer's page both reading it. Read from
 * the sources and the shipped files; no server.
 */
class TrackLangKeysTest {

    private static final Path LANGUAGES = Path.of("src", "main", "resources", "Server", "Languages", "en-US");

    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final Pattern TRACK_LINE = Pattern.compile("TrackText\\.line\\(\"([a-z][a-z0-9_.]*)\"");

    /** {@code TrackText}'s own calls of its {@code line}, unqualified. */
    private static final Pattern OWN_LINE = Pattern.compile("(?<![\\w.])line\\(\"([a-z][a-z0-9_.]*)\"");

    private static final Pattern TREE_LINE = Pattern.compile("TreeText\\.line\\(\"([a-z][a-z0-9_.]*)\"");

    @Test
    void everyRefusalAndRespecLineShipsInEnglish() throws IOException {
        Set<String> shipped = shipped("ziggfreedcommon.track.lang");
        Set<String> missing = new TreeSet<>();
        Message track = Msg.raw("Track");
        for (PickService.Status status : PickService.Status.values()) {
            check(TrackText.refusal(status, 2, track), shipped, missing);
            check(TrackText.refusal(status), shipped, missing);
            check(TrackText.respecLine(status, 3, track), shipped, missing);
        }
        check(TrackText.levelNeeded(5, track), shipped, missing);
        assertEquals(Set.of(), missing, "lines the refusal mapping says but the English track file does not ship");
    }

    @Test
    void everyTrackLineSpelledShipsInEnglish() throws IOException {
        Set<String> said = new TreeSet<>();
        for (Path file : sources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            collect(TRACK_LINE.matcher(text), said);
            if (file.getFileName().toString().equals("TrackText.java")) {
                collect(OWN_LINE.matcher(text), said);
            }
        }
        assertTrue(said.size() >= 10, "the scan found almost nothing, so it is not scanning: " + said);
        Set<String> shipped = shipped("ziggfreedcommon.track.lang");
        assertEquals(List.of(), said.stream().filter(key -> !shipped.contains(key)).toList(),
                "lines the track module says but its English file does not ship");
    }

    @Test
    void everyTreeLineSpelledShipsInEnglish() throws IOException {
        Set<String> said = new TreeSet<>();
        for (Path file : sources()) {
            collect(TREE_LINE.matcher(Files.readString(file, StandardCharsets.UTF_8)), said);
        }
        assertTrue(said.size() >= 10, "the scan found almost nothing, so it is not scanning: " + said);
        Set<String> shipped = shipped("ziggfreedcommon.tree.lang");
        assertEquals(List.of(), said.stream().filter(key -> !shipped.contains(key)).toList(),
                "lines the tree page says but its English file does not ship");
    }

    /** A line's key, its family's prefix taken off, into {@code missing} when the file does not ship it. */
    private static void check(Message line, Set<String> shipped, Set<String> missing) {
        String id = line.getMessageId();
        assertTrue(id != null && id.startsWith(TrackText.PREFIX), "not a track line: " + id);
        String key = id.substring(TrackText.PREFIX.length());
        if (!shipped.contains(key)) {
            missing.add(key);
        }
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static void collect(Matcher m, Set<String> into) {
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    /** The keys the English file {@code name} ships. */
    private static Set<String> shipped(String name) throws IOException {
        Path file = LANGUAGES.resolve(name);
        assertTrue(Files.isRegularFile(file), "missing " + file.toAbsolutePath());
        Set<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                continue;
            }
            out.add(trimmed.substring(0, eq).trim());
        }
        return out;
    }
}
