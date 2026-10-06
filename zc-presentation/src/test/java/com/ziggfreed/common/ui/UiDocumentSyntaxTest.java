package com.ziggfreed.common.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.panel.HudPanelLayout;
import com.ziggfreed.common.ui.hud.panel.LedgerPanelHud;
import com.ziggfreed.common.ui.hud.panel.WorldPanelHud;

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

    /** A label's alignment value, whatever word it is spelt with. */
    private static final Pattern ALIGNMENT = Pattern.compile("\\b((?:Vertical|Horizontal)Alignment)\\s*:\\s*([A-Za-z]+)");

    /** The client's LabelAlignment values: vanilla aligns a label to the bottom with End, never Bottom. */
    private static final Set<String> ALIGNMENTS = Set.of("Start", "Center", "End");

    /** An import, {@code $Alias = "relative/path.ui";}. */
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*\\$([A-Za-z][A-Za-z0-9]*)\\s*=\\s*\"([^\"]+)\"\\s*;");

    /** The one vanilla document this module's documents import (as {@code $C}); the game ships it, not this module. */
    private static final String VANILLA_COMMON = "Common.ui";

    /** A reference into an import, {@code $Alias.@Name}. */
    private static final Pattern REFERENCE = Pattern.compile("\\$([A-Za-z][A-Za-z0-9]*)\\.@([A-Za-z][A-Za-z0-9]*)");

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
    void everyAlignmentIsAValueTheClientAccepts() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path doc : documents()) {
            String text = Files.readString(doc, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = ALIGNMENT.matcher(stripComment(lines[i]));
                while (m.find()) {
                    if (!ALIGNMENTS.contains(m.group(2))) {
                        bad.add(doc.getFileName() + ":" + (i + 1) + " " + m.group(1) + ": " + m.group(2));
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "a label's alignment is Start, Center or End (vanilla's LabelAlignment); Top, Bottom, Left or "
                        + "Right fails the client's parse and disconnects every player at load. Offending: " + bad);
    }

    @Test
    void everyCrossDocumentReferenceResolves() throws IOException {
        Path root = customRoot();
        List<String> bad = new ArrayList<>();
        for (Path doc : documents()) {
            String text = stripComments(Files.readString(doc, StandardCharsets.UTF_8));
            Map<String, Path> imports = new HashMap<>();
            Matcher imported = IMPORT.matcher(text);
            while (imported.find()) {
                imports.put(imported.group(1), doc.getParent().resolve(imported.group(2)).normalize());
            }
            Matcher ref = REFERENCE.matcher(text);
            while (ref.find()) {
                Path target = imports.get(ref.group(1));
                String where = root.relativize(doc) + " $" + ref.group(1) + ".@" + ref.group(2);
                if (target == null) {
                    bad.add(where + ": no $" + ref.group(1) + " import");
                } else if (!Files.isRegularFile(target) && !target.equals(root.resolve(VANILLA_COMMON))) {
                    bad.add(where + ": imports " + root.relativize(target) + ", which neither this module nor the "
                            + "game ships");
                } else if (Files.isRegularFile(target) && !defines(target, ref.group(2))) {
                    bad.add(where + ": " + root.relativize(target) + " defines no @" + ref.group(2));
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "every $Alias.@Name names an import the document declares and a value that document defines (a "
                        + "type-scale step, a style); one that does not resolve fails the client's parse and disconnects "
                        + "every player at load. Vanilla's own documents are not read here. Offending: " + bad);
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
        for (HudPanelLayout layout : List.of(LedgerPanelHud.LAYOUT, WorldPanelHud.LAYOUT)) {
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

    /** Does {@code doc} define {@code @name} (a value, a style or a template)? */
    private static boolean defines(Path doc, String name) throws IOException {
        return Pattern.compile("(?m)^\\s*@" + Pattern.quote(name) + "\\s*=")
                .matcher(stripComments(Files.readString(doc, StandardCharsets.UTF_8))).find();
    }

    /** This module's {@code Common/UI/Custom} directory, the root every document path is relative to. */
    private static Path customRoot() {
        Path root = Paths.get("src/main/resources/Common/UI/Custom");
        if (!Files.isDirectory(root)) {
            // Run from the repository root rather than the module: same tree, longer path.
            root = Paths.get("zc-presentation/src/main/resources/Common/UI/Custom");
        }
        assertTrue(Files.isDirectory(root), "no Custom UI directory found to check, at " + root.toAbsolutePath());
        return root.toAbsolutePath().normalize();
    }

    /** Every {@code .ui} this module ships. */
    private static List<Path> documents() throws IOException {
        Path root = customRoot();
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
