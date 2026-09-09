package com.ziggfreed.common.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.bar.HudBarGridHud;
import com.ziggfreed.common.ui.hud.bar.HudBarLayout;
import com.ziggfreed.common.ui.hud.bar.HudBarStackHud;

/**
 * Holds every shipped {@code .ui} document to what the client's parser will actually accept.
 *
 * <p><b>Why this exists.</b> A {@code .ui} is not compiled and the SERVER never reads one: Custom UI
 * documents are parsed by the client, so a headless boot check loads, validates and runs a whole
 * server without ever discovering that a document cannot be parsed at all. The failure surfaces at
 * the worst possible moment instead, when a player joins: the client reports
 * {@code Failed to load CustomUI documents} and refuses to finish loading, and the server logs only
 * a cancelled world join. Nothing between writing the file and a human joining a game would say a
 * word. These checks close the part of that gap a machine can close.
 *
 * <p>An ELEMENT ID is the rule that bites hardest, because it looks like it should be free-form and
 * is not: an id is a letter followed by letters and digits, and the parser stops at the first
 * character outside that set. An underscore in particular reads as a perfectly ordinary separator
 * to anyone naming a grid slot, and is not one here.
 */
class UiDocumentSyntaxTest {

    /** What the client's parser accepts as an element id: a letter, then letters and digits. */
    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");

    /** Every {@code #...} token, however malformed, so one the {@link #ID} shape rejects is still seen. */
    private static final Pattern ID_TOKEN = Pattern.compile("#([A-Za-z][A-Za-z0-9_.\\-]*)");

    @Test
    void everyElementIdIsLettersAndDigitsOnly() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path doc : documents()) {
            String text = Files.readString(doc, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String code = stripComment(line);
                Matcher m = ID_TOKEN.matcher(code);
                while (m.find()) {
                    String id = m.group(1);
                    // A selector's trailing ".Property" is not part of the id, so read only up to it.
                    String head = id.split("\\.")[0];
                    if (!ID.matcher("#" + head).matches()) {
                        bad.add(doc.getFileName() + ": #" + head);
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "a UI element id must be a letter followed by letters and digits; the client's parser "
                        + "stops at anything else and the whole document then fails to load, which no "
                        + "server-side check can see. Offending ids: " + bad);
    }

    @Test
    void everyDocumentHasBalancedBraces() throws IOException {
        for (Path doc : documents()) {
            String text = stripComments(Files.readString(doc, StandardCharsets.UTF_8));
            int depth = 0;
            for (char c : text.toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
                if (depth < 0) {
                    fail(doc.getFileName() + " closes a brace it never opened");
                }
            }
            assertTrue(depth == 0, doc.getFileName() + " leaves " + depth + " brace(s) open");
        }
    }

    @Test
    void everySlotTheBarPanelsAddressActuallyExistsInTheirDocuments() throws IOException {
        for (HudBarLayout layout : List.of(HudBarStackHud.LAYOUT, HudBarGridHud.LAYOUT)) {
            Path doc = document(layout.template());
            String text = Files.readString(doc, StandardCharsets.UTF_8);
            assertTrue(text.contains(layout.root()),
                    layout.template() + " does not declare its own root " + layout.root());
            for (int column = 0; column < layout.columns(); column++) {
                assertTrue(text.contains(layout.columnSelector(column) + " "),
                        layout.template() + " does not declare column " + column);
                for (int row = 0; row < layout.slotsPerColumn(); row++) {
                    // The selector is a descendant path; the slot's own id is its last segment.
                    String[] parts = layout.slotSelector(column, row).split(" ");
                    String slot = parts[parts.length - 1];
                    assertTrue(text.contains(slot + " "),
                            layout.template() + " does not declare slot " + slot
                                    + ", which the paint addresses on every push; a command against a "
                                    + "selector the document lacks crashes the client");
                }
            }
        }
    }

    /** The shipped document at a layout's template path. */
    private static Path document(String template) throws IOException {
        for (Path doc : documents()) {
            if (doc.toString().replace(java.io.File.separatorChar, '/').endsWith(template)) {
                return doc;
            }
        }
        throw new AssertionError("no shipped document at " + template);
    }

    /** Every {@code .ui} this module ships. */
    private static List<Path> documents() throws IOException {
        Path root = Paths.get("src/main/resources/Common/UI/Custom");
        if (!Files.isDirectory(root)) {
            // Run from the repository root rather than the module: same tree, longer path.
            root = Paths.get("zc-presentation/src/main/resources/Common/UI/Custom");
        }
        assertTrue(Files.isDirectory(root), "no Custom UI directory found to check, at " + root.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> docs = walk.filter(p -> p.toString().endsWith(".ui")).sorted().toList();
            assertTrue(!docs.isEmpty(), "no .ui documents found under " + root.toAbsolutePath());
            return docs;
        }
    }

    /** A line with its {@code //} comment removed; a comment may say anything. */
    private static String stripComment(String line) {
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }

    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n")) {
            out.append(stripComment(line)).append('\n');
        }
        return out.toString();
    }
}
