package com.ziggfreed.common.progress.asset;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveKindRegistry;

/**
 * The seeded vocabulary and the shipped kind files are two descriptions of the same kinds, and they
 * never drift: every kind the registry seeds ships a file that describes it, and every file this
 * module ships names a seeded kind.
 *
 * <p>A seeded kind with no file is a step nothing pictures and nothing words; a file naming an id
 * the registry does not seed is a kind every consumer would have to add for itself. Both were
 * one edit away from shipping, so the two sets are held equal here, by name.
 */
class ShippedKindFilesTest {

    private static final Path KINDS = Path.of("src", "main", "resources", "Server",
            "ZiggfreedCommon", "ObjectiveKinds");

    @Test
    void everySeededKindShipsAFileAndEveryFileNamesASeededKind() throws IOException {
        // The ledger lists ids folded; the file names and the canonical spelling are upper-case.
        Set<String> seeded = new TreeSet<>();
        for (String id : new ObjectiveKindRegistry().ids()) {
            seeded.add(id.toUpperCase(Locale.ROOT));
        }
        Set<String> shipped = shippedIds();
        List<String> problems = new ArrayList<>();
        for (String id : seeded) {
            if (!shipped.contains(id)) {
                problems.add("seeded kind " + id + " ships no " + KINDS + "/" + fileNameFor(id) + ".json");
            }
        }
        for (String id : shipped) {
            if (!seeded.contains(id)) {
                problems.add(KINDS + " ships " + id + ", which ObjectiveKindRegistry does not seed");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /** The ids the shipped files describe: the file name, upper-cased, the way a step writes its Kind. */
    private static Set<String> shippedIds() throws IOException {
        assertTrue(Files.isDirectory(KINDS), "missing " + KINDS.toAbsolutePath());
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.list(KINDS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString();
                out.add(name.substring(0, name.length() - ".json".length()).toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** {@code KILL_ENTITY} spelled the way its file is: {@code Kill_Entity}. */
    private static String fileNameFor(String id) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : id.toCharArray()) {
            sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = c == '_';
        }
        return sb.toString();
    }
}
