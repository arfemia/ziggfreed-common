package com.ziggfreed.common.party.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Every id the party screen names in its source is declared in one of its documents (the page, its row) or the band
 * frame it sits on: a command on an id the page lacks disconnects the player, and the screen's restyle took ids away
 * (its own title and tab buttons). The ids inside a kit template it appends (the view tabs, the hero) are their
 * painters', which {@code KitSelectorsTest} walks.
 */
class PartyPageIdsTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "party", "page",
            "PartyInvitePage.java");

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");

    private static final Path FRAMES = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common", "ZigFrames.ui");

    /** A string literal in the page's code. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    /** An id inside a literal (a selector's first segment, or a row's child after its {@code sel + " "}). */
    private static final Pattern NAMED = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");

    @Test
    void everyIdThePartyScreenNamesIsDeclaredInItsDocumentsOrItsFrame() throws IOException {
        String documents = read(PAGES.resolve("ZigPartyPage.ui")) + "\n" + read(PAGES.resolve("ZigPartyRow.ui"))
                + "\n" + read(FRAMES);
        Set<String> named = new TreeSet<>();
        for (String line : Files.readAllLines(SOURCE, StandardCharsets.UTF_8)) {
            String code = line.trim();
            if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) {
                continue; // javadoc and comments name ids in prose
            }
            Matcher literal = LITERAL.matcher(line);
            while (literal.find()) {
                Matcher m = NAMED.matcher(literal.group(1));
                while (m.find()) {
                    named.add(m.group(1));
                }
            }
        }
        assertTrue(named.contains("RowName") && named.contains("RowBadge"),
                "a row's child ids are scanned too: " + named);
        assertTrue(named.size() >= 10, "the scan found almost no ids, so it is not scanning: " + named);
        List<String> missing = new ArrayList<>();
        for (String id : named) {
            Pattern declared = Pattern.compile("[A-Za-z@.$]+\\s+#" + Pattern.quote(id) + "\\s*\\{");
            if (!declared.matcher(documents).find()) {
                missing.add("#" + id);
            }
        }
        assertEquals(List.of(), missing, "the party screen names only ids its documents or its frame declare");
    }

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing " + file.toAbsolutePath());
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n", -1)) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }
}
