package com.ziggfreed.common.track.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The Award Boost screen's document and the Java that drives it, held together: a command against an id the document
 * lacks disconnects the player, so every id the page's Java spells is declared in its document or by the frame; and
 * every word the document names inline is a key the en-US boost lang file ships, or the client shows the raw key.
 */
class BoostGivePageDocumentTest {

    private static final Path DOCUMENT = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages",
            "ZigBoostGivePage.ui");

    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "track", "boost",
            "BoostGivePage.java");

    private static final Path FRAMES = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common", "ZigFrames.ui");

    private static final Path LANG = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.boost.lang");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern INLINE_KEY = Pattern.compile("%ziggfreedcommon\\.boost\\.([A-Za-z0-9.]+);");

    @Test
    void everyIdThePageAddressesIsDeclaredInItsDocument() throws IOException {
        String declared = read(DOCUMENT) + read(FRAMES);
        Set<String> spelled = new TreeSet<>();
        Matcher literal = STRING_LITERAL.matcher(Files.readString(PAGE, StandardCharsets.UTF_8));
        while (literal.find()) {
            Matcher id = ID.matcher(literal.group(1));
            while (id.find()) {
                spelled.add(id.group());
            }
        }
        assertFalse(spelled.isEmpty(), "the page addresses its document");
        List<String> missing = new ArrayList<>();
        for (String id : spelled) {
            if (!Pattern.compile("(?m)(^|\\s)(?:[A-Z][A-Za-z]*|\\$[A-Za-z]+\\.@[A-Za-z0-9]+)\\s+"
                    + Pattern.quote(id) + "\\s*\\{").matcher(declared).find()) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing, "ids the Award Boost screen's Java sets that its document does not declare");
    }

    @Test
    void everyInlineWordIsAShippedKey() throws IOException {
        Set<String> shipped = new TreeSet<>();
        for (String line : Files.readAllLines(LANG, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (!line.startsWith("#") && eq > 0) {
                shipped.add(line.substring(0, eq).trim());
            }
        }
        List<String> missing = new ArrayList<>();
        Matcher key = INLINE_KEY.matcher(read(DOCUMENT));
        while (key.find()) {
            if (!shipped.contains(key.group(1))) {
                missing.add(key.group(1));
            }
        }
        assertEquals(List.of(), missing, "inline keys of the Award Boost screen that ziggfreedcommon.boost.lang lacks");
    }

    /** A document with its {@code //} comments taken out. */
    private static String read(Path path) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }
}
