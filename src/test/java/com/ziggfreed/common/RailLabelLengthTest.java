package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * Every label on the library's four menu tabs ships in English and stays on one line at the rail's width
 * in every locale the library ships, counted in characters once uppercased, as the rail draws it
 * (MenuFrame.RAIL_LABEL_MAX_CHARS, the body size's proxy for width). A locale that lacks a key falls back to
 * English, which is checked.
 */
class RailLabelLengthTest {

    private record Label(String module, String file, String key) {
    }

    private static final List<Label> LABELS = List.of(
            new Label("zc-objectives", "ziggfreedcommon.progression.lang", "book.tab.quests"),
            new Label("zc-objectives", "ziggfreedcommon.progression.lang", "book.tab.achievements"),
            new Label("zc-almanac", "ziggfreedcommon.almanac.lang", "title"),
            new Label("zc-instance", "ziggfreedcommon.leaderboard.lang", "menu.records"));

    @Test
    void everyTabLabelShipsInEnglishAndFitsTheRailInEveryLocale() throws IOException {
        for (Label label : LABELS) {
            Path languages = Path.of(label.module(), "src", "main", "resources", "Server", "Languages");
            assertTrue(values(languages.resolve("en-US").resolve(label.file())).containsKey(label.key()),
                    "en-US " + label.file() + " must author " + label.key());
            try (Stream<Path> locales = Files.list(languages)) {
                for (Path locale : locales.filter(Files::isDirectory).sorted().toList()) {
                    Path file = locale.resolve(label.file());
                    String value = Files.exists(file) ? values(file).get(label.key()) : null;
                    if (value == null) {
                        continue;
                    }
                    assertTrue(shownLength(value) <= MenuFrame.RAIL_LABEL_MAX_CHARS, locale.getFileName() + " "
                            + label.key() + " = '" + value + "' is longer than a rail label can be on one line");
                }
            }
        }
    }

    @Test
    void theMeasureIsTheLabelAsTheRailDrawsIt() {
        assertEquals("STRASSE".length(), shownLength("Straße"), "the rail draws capitals, and a capital ß is two letters");
    }

    /** A label's length as the rail shows it: uppercased (the row's RenderUppercase), whatever the locale. */
    static int shownLength(String value) {
        return value.toUpperCase(Locale.ROOT).length();
    }

    private static Map<String, String> values(Path file) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && eq > 0) {
                out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
