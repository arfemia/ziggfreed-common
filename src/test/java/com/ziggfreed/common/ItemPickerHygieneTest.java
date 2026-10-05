package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * A leaf naming an item id offers the engine's own item picker ({@code EditorSchema.assetRef(Item.class)}),
 * never an editor pick list called {@code hytale:item}: nothing on the server serves a list by that name,
 * so the editor shows an empty one. The scan and its comment and string masking are
 * {@link NumberDisplayHygieneTest}'s, so the hygiene rules agree about what counts as code.
 */
class ItemPickerHygieneTest {

    /** A dropdown over the list nobody serves, written with or without the {@code UIEditor.} prefix. */
    private static final Pattern UNSERVED_ITEM_LIST = Pattern.compile("\\bDropdown\\(\\s*\"hytale:item\"");

    @Test
    void noLeafNamesTheItemListNobodyServes() throws IOException {
        List<Path> roots = NumberDisplayHygieneTest.sourceRoots();
        assertTrue(!roots.isEmpty(), "no main-source roots found to scan");
        List<String> problems = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> entries = Files.walk(root)) {
                for (Path p : entries.filter(f -> f.getFileName().toString().endsWith(".java"))
                        .sorted().toList()) {
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (int lineNo : scanLines(lines)) {
                        problems.add(p + ":" + lineNo + ": " + lines.get(lineNo - 1).trim());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " leaf(s) name the hytale:item pick list, "
                + "which no server serves; write .metadata(EditorSchema.assetRef(Item.class)) for the "
                + "engine's own item picker:\n" + String.join("\n", problems));
    }

    /** The detection over an in-memory line list, so the fixtures below exercise it directly. */
    @Nonnull
    static List<Integer> scanLines(@Nonnull List<String> lines) {
        List<Integer> out = new ArrayList<>();
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean startedInBlockComment = inBlockComment;
            inBlockComment = NumberDisplayHygieneTest.updateBlockCommentState(line, inBlockComment);
            if (startedInBlockComment || NumberDisplayHygieneTest.isImportPackageOrLineComment(line)) {
                continue;
            }
            Matcher m = UNSERVED_ITEM_LIST.matcher(line);
            if (NumberDisplayHygieneTest.findUnmasked(m, line)) {
                out.add(i + 1);
            }
        }
        return out;
    }

    // ==================== fixtures: prove the rule works ====================

    @Test
    void flagsTheDropdownWithOrWithoutItsPrefix() {
        assertEquals(List.of(2, 3), scanLines(List.of(
                "class Foo {",
                "    X a = new UIEditor(new UIEditor.Dropdown(\"hytale:item\"));",
                "    X b = new UIEditor(new Dropdown( \"hytale:item\"));",
                "}")));
    }

    @Test
    void acceptsTheEnginesPickerAndAListSomebodyServes() {
        assertTrue(scanLines(List.of(
                "class Foo {",
                "    X a = EditorSchema.assetRef(Item.class);",
                "    X b = new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.SHOPS));",
                "}")).isEmpty());
    }

    @Test
    void ignoresAMentionInAJavadocBlockOrALineComment() {
        assertTrue(scanLines(List.of(
                "/**",
                " * Never new UIEditor.Dropdown(\"hytale:item\"): nothing serves it.",
                " */",
                "class Foo {",
                "    // new UIEditor.Dropdown(\"hytale:item\")",
                "    X a = EditorSchema.assetRef(Item.class); // not Dropdown(\"hytale:item\")",
                "}")).isEmpty());
    }
}
