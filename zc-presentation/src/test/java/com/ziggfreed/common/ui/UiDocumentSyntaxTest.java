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
 * Holds every shipped {@code .ui} document to what the client's parser will actually accept: every zc module's,
 * not only this module's, since the modules merge into one jar and their documents into one Custom UI tree (a page
 * in zc-objectives imports this module's {@code Common/ZigType.ui} and kit documents by path).
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
        List<String> bad = new ArrayList<>();
        for (Path doc : documents()) {
            Path root = rootOf(doc);
            String text = stripComments(Files.readString(doc, StandardCharsets.UTF_8));
            Map<String, Path> imports = new HashMap<>();
            Matcher imported = IMPORT.matcher(text);
            while (imported.find()) {
                // An import is relative to its document; every module's documents merge into one Custom UI tree.
                imports.put(imported.group(1), root.relativize(doc.getParent().resolve(imported.group(2)).normalize()));
            }
            Matcher ref = REFERENCE.matcher(text);
            while (ref.find()) {
                Path target = imports.get(ref.group(1));
                String where = moduleOf(root) + ": " + root.relativize(doc) + " $" + ref.group(1) + ".@" + ref.group(2);
                Path shipped = target == null ? null : shipped(target);
                if (target == null) {
                    bad.add(where + ": no $" + ref.group(1) + " import");
                } else if (shipped == null && !target.equals(Paths.get(VANILLA_COMMON))) {
                    bad.add(where + ": imports " + target + ", which neither a zc module nor the game ships");
                } else if (shipped != null && !defines(shipped, ref.group(2))) {
                    bad.add(where + ": " + target + " defines no @" + ref.group(2));
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

    /** This module's {@code Common/UI/Custom} directory. */
    private static Path customRoot() {
        Path root = Paths.get("src/main/resources/Common/UI/Custom");
        if (!Files.isDirectory(root)) {
            // Run from the repository root rather than the module: same tree, longer path.
            root = Paths.get("zc-presentation/src/main/resources/Common/UI/Custom");
        }
        assertTrue(Files.isDirectory(root), "no Custom UI directory found to check, at " + root.toAbsolutePath());
        return root.toAbsolutePath().normalize();
    }

    /**
     * Every zc module's {@code Common/UI/Custom} directory: the modules merge into one jar, so their documents share
     * one tree on the client, and a page in zc-objectives imports {@code Common/ZigType.ui} from this module.
     */
    private static List<Path> customRoots() throws IOException {
        // Custom <- UI <- Common <- resources <- main <- src <- the module <- the repository
        Path repository = customRoot().getParent().getParent().getParent().getParent().getParent().getParent()
                .getParent();
        try (Stream<Path> modules = Files.list(repository)) {
            List<Path> roots = modules.filter(m -> m.getFileName().toString().startsWith("zc-"))
                    .map(m -> m.resolve("src/main/resources/Common/UI/Custom")).filter(Files::isDirectory).sorted()
                    .toList();
            assertTrue(roots.contains(customRoot()), "this module is one of the roots read: " + roots);
            return roots;
        }
    }

    /** The {@code Common/UI/Custom} directory a document ships under. */
    private static Path rootOf(Path doc) throws IOException {
        for (Path root : customRoots()) {
            if (doc.startsWith(root)) {
                return root;
            }
        }
        throw new AssertionError(doc + " is under no module's Custom UI directory");
    }

    /** The module a {@code Common/UI/Custom} directory belongs to, for a failure message. */
    private static String moduleOf(Path root) {
        // Custom <- UI <- Common <- resources <- main <- src <- the module
        return root.getParent().getParent().getParent().getParent().getParent().getParent().getFileName().toString();
    }

    /** The shipped file at a path under {@code Common/UI/Custom}, in whichever zc module ships it; null when none. */
    private static Path shipped(Path underCustom) throws IOException {
        for (Path root : customRoots()) {
            Path file = root.resolve(underCustom);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    /** Every {@code .ui} every zc module ships. */
    private static List<Path> documents() throws IOException {
        List<Path> docs = new ArrayList<>();
        for (Path root : customRoots()) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".ui")).sorted().forEach(docs::add);
            }
        }
        assertTrue(docs.stream().anyMatch(d -> d.startsWith(customRoot())), "no .ui documents found under "
                + customRoot());
        assertTrue(docs.stream().anyMatch(d -> !d.startsWith(customRoot())),
                "the other zc modules' documents are read too (zc-objectives ships pages)");
        return docs;
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
