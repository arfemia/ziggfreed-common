package com.ziggfreed.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the shipped {@code Server/Languages/<bcp47>/*.lang} files against the translation mistakes
 * that silently break rendering at runtime:
 *
 * <ul>
 *   <li><b>Placeholder integrity</b>: a translated value must use exactly the same positional
 *       {@code {0}}/{@code {1}} placeholders as the en-US value for that key, or substitution drops
 *       arguments (or prints a literal {@code {1}} at a player).</li>
 *   <li><b>No em-dashes</b>: banned repo-wide, including every {@code .lang} value.</li>
 *   <li><b>No duplicate keys</b>: a duplicate silently shadows the earlier value, so the line
 *       somebody edited is not the line that renders.</li>
 * </ul>
 *
 * <p>Key COVERAGE (every en-US key present in every language) is deliberately NOT asserted: missing
 * keys fall back to English per key through the engine's own localization module, so a gap is a
 * translation backlog rather than a broken build.
 *
 * <p>The files live in a module while this test runs at the ROOT, which is where the whole shipped
 * jar is assembled - so a language file added by any module in future is covered by pointing
 * {@link #LANG_ROOTS} at it, with nothing else to change.
 */
class LangFileIntegrityTest {

    /**
     * Every module resource root holding shipped language files. One entry per module that ships
     * any; the checks below are per FILE NAME, so a module carrying several domain files (or a
     * file name another module also uses, like {@code items.lang}) needs nothing else here.
     */
    private static final List<Path> LANG_ROOTS = List.of(
            Path.of("zc-core", "src", "main", "resources", "Server", "Languages"),
            Path.of("zc-presentation", "src", "main", "resources", "Server", "Languages"),
            Path.of("zc-progression", "src", "main", "resources", "Server", "Languages"),
            Path.of("zc-objectives", "src", "main", "resources", "Server", "Languages"),
            Path.of("zc-loot", "src", "main", "resources", "Server", "Languages"),
            Path.of("zc-commerce", "src", "main", "resources", "Server", "Languages"),
            Path.of("zc-encounter", "src", "main", "resources", "Server", "Languages"));

    private static final String EN_US = "en-US";

    // Matches the leading "{N" of a placeholder token regardless of what follows the index - a bare
    // "{0}", a "{0, number}" format spec, or a "{0, plural, ...}" selector all start the same way, so
    // this captures the same index for all three forms. Only the opening is asserted; a translator
    // may legally drop or keep the trailing format spec as long as the index SET matches.
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*(\\d+)\\s*[,}]");

    /** The banned character, spelled by code point so this file never carries one itself. */
    private static final char EM_DASH = (char) 0x2014;

    @Test
    void translationsKeepPlaceholdersBanEmDashesAndHaveNoDuplicateKeys() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path root : LANG_ROOTS) {
            assertTrue(Files.isDirectory(root), "missing lang root: " + root.toAbsolutePath());
            checkRoot(root, problems);
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " lang problems:\n"
                + String.join("\n", problems));
    }

    private static void checkRoot(Path root, List<String> problems) throws IOException {
        Map<String, Map<String, String>> englishByFile = new LinkedHashMap<>();
        Path englishDir = root.resolve(EN_US);
        if (Files.isDirectory(englishDir)) {
            try (Stream<Path> files = Files.list(englishDir)) {
                for (Path file : langFiles(files)) {
                    englishByFile.put(file.getFileName().toString(), parse(file, new ArrayList<>()));
                }
            }
        }

        try (Stream<Path> locales = Files.list(root)) {
            for (Path localeDir : locales.filter(Files::isDirectory).sorted().toList()) {
                String locale = localeDir.getFileName().toString();
                try (Stream<Path> files = Files.list(localeDir)) {
                    for (Path langFile : langFiles(files)) {
                        checkFile(locale, langFile, englishByFile, problems);
                    }
                }
            }
        }
    }

    private static void checkFile(String locale, Path langFile,
                                  Map<String, Map<String, String>> englishByFile, List<String> problems) {
        String where = locale + "/" + langFile.getFileName();
        List<String> dupes = new ArrayList<>();
        Map<String, String> entries;
        try {
            entries = parse(langFile, dupes);
        } catch (IOException e) {
            problems.add(where + ": unreadable - " + e.getMessage());
            return;
        }
        for (String dupe : dupes) {
            problems.add(where + ": duplicate key '" + dupe + "'");
        }
        Map<String, String> english = englishByFile.getOrDefault(langFile.getFileName().toString(), Map.of());
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getValue().indexOf(EM_DASH) >= 0) {
                problems.add(where + ": em-dash in '" + e.getKey() + "'");
            }
            if (EN_US.equals(locale)) {
                continue;
            }
            String en = english.get(e.getKey());
            if (en != null && !placeholders(en).equals(placeholders(e.getValue()))) {
                problems.add(where + ": placeholder mismatch on '" + e.getKey() + "' (en: "
                        + placeholders(en) + ", translated: " + placeholders(e.getValue()) + ")");
            }
        }
    }

    /**
     * Fixture proving the SET-based comparison does both halves of its job: a legitimate plural reuse
     * (the same index bound once outside a plural clause and again inside it) must not fail just
     * because a translation repeats that index a different number of times, while a translation that
     * genuinely drops an argument English carries must still be caught.
     */
    @Test
    void placeholderSetIgnoresRepeatCountButCatchesRealMismatch() {
        assertEquals(
                placeholders("Summons {0} {0, plural, one{ally} other{allies}} for {1}s."),
                placeholders("Beschwoert fuer {1}s {0} Verbuendete."));
        assertNotEquals(
                placeholders("Deals {0} damage every {1}s."),
                placeholders("Verursacht {0} Schaden."));
    }

    private static List<Path> langFiles(Stream<Path> files) {
        return files.filter(p -> p.getFileName().toString().endsWith(".lang")).sorted().toList();
    }

    /** {@code key -> value} for one .lang file; duplicate keys are reported via {@code dupesOut}. */
    private static Map<String, String> parse(Path file, List<String> dupesOut) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String s = line.strip();
            int eq = s.indexOf('=');
            if (s.isEmpty() || s.startsWith("#") || eq <= 0) {
                continue;
            }
            String key = s.substring(0, eq).strip();
            if (out.put(key, s.substring(eq + 1).strip()) != null) {
                dupesOut.add(key);
            }
        }
        return out;
    }

    /**
     * The SET of positional placeholder indices a value uses ({@code "{0}{0}{1}"} yields
     * {@code {0, 1}}). A set rather than a per-index count: the plural convention legitimately binds
     * one index more than once, and a translation may need a different repeat count for its own
     * grammar, or none at all where the target language folds the plural into a single clause.
     * Comparing sets still catches the real defect - an index English carries and the translation
     * drops, or one the translation invents.
     */
    private static Set<Integer> placeholders(String value) {
        Set<Integer> indices = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            indices.add(Integer.parseInt(m.group(1)));
        }
        return indices;
    }
}
