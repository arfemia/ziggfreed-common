package com.ziggfreed.common.instance.dungeon.tier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The gate page's selectors: a command against an element its documents lack disconnects the player ("Selected element
 * in CustomUI command was not found"), and zc-presentation's selector test sees only the kit's own pages. So every id
 * the page's Java addresses by a literal ({@code "#GateHead.Visible"}, {@code " #ChestName.TextSpans"}) must be one
 * its page document, its chest row or the band frame it sits on declares; the kit's painters own the cards', lines' and
 * slots' ids and are held by that test. The hero is named by its own insertion block at the frame's level, the form
 * the client resolves wherever the frame's template holds it, never one nested inside {@code #Content}'s.
 */
class DungeonGatePageDocumentTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");
    private static final Path PAGE_DOC = PAGES.resolve("ZigDungeonGatePage.ui");
    private static final Path CHEST_DOC = PAGES.resolve("ZigDungeonGateChest.ui");
    private static final Path FRAMES = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common", "ZigFrames.ui");
    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
            "dungeon", "tier", "DungeonGatePage.java");

    /** An element declaration: a type, then an id, then its block. */
    private static final Pattern DECLARED = Pattern.compile("\\b[A-Z]\\w*\\s+(#[A-Za-z][A-Za-z0-9]*)\\s*\\{");

    /** A Java string literal on one line. */
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    /** A Java comment: a block comment (javadoc included) or a line comment. */
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /** An id inside a literal: a {@code #} then the client's id shape. */
    private static final Pattern ID = Pattern.compile("#[A-Za-z][A-Za-z0-9]*");

    @Test
    void everyIdThePageAddressesIsDeclaredByItsDocumentsOrItsFrame() throws IOException {
        Set<String> declared = new LinkedHashSet<>();
        declared.addAll(declared(code(PAGE_DOC)));
        declared.addAll(declared(code(CHEST_DOC)));
        declared.addAll(declared(code(FRAMES)));
        assertTrue(declared.contains("#GateCards") && declared.contains("#ChestName") && declared.contains("#Hero"),
                "the scan found too little, so it is not scanning: " + declared);

        List<String> addressed = addressed(Files.readString(PAGE, StandardCharsets.UTF_8));
        assertTrue(addressed.contains("#GateHead") && addressed.contains("#ChestTaken"),
                "the page's literals were not found, so it is not scanning: " + addressed);
        List<String> missing = new ArrayList<>();
        for (String id : addressed) {
            if (!declared.contains(id)) {
                missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(), "the gate page addresses ids no document of its declares: " + missing);
    }

    @Test
    void theHeroIsNamedAtTheFramesLevelAndTheContentIsLaidOutInColumns() throws IOException {
        String page = code(PAGE_DOC);
        assertTrue(page.contains("$F.@ZigDecoratedFrame"), "the gate sits on the band frame");
        int content = page.indexOf("#Content {");
        int hero = page.indexOf("#Hero {");
        assertTrue(content > 0 && hero > 0, "both the frame's content and its hero are named");
        assertFalse(blockAt(page, content).contains("#Hero"),
                "the hero's insertion stands at the frame's level, never nested inside #Content's");
        assertTrue(blockAt(page, content).contains("LayoutMode: Left"), "the hero is the first of two columns");
    }

    /** Every id declared in {@code code} with a type before it. */
    @Nonnull
    private static List<String> declared(@Nonnull String code) {
        List<String> out = new ArrayList<>();
        Matcher m = DECLARED.matcher(code);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Every id named inside a string literal of {@code java}'s code, each once, in order: comments are taken out first,
     * so a javadoc's {@code {@link Type#member}} counts as nothing.
     */
    @Nonnull
    private static List<String> addressed(@Nonnull String java) {
        String code = COMMENT.matcher(java).replaceAll(" ");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = LITERAL.matcher(code);
        while (m.find()) {
            Matcher id = ID.matcher(m.group());
            while (id.find()) {
                out.add(id.group());
            }
        }
        return new ArrayList<>(out);
    }

    /** The block that opens at the brace after {@code from}, braces balanced. */
    @Nonnull
    private static String blockAt(@Nonnull String code, int from) {
        int open = code.indexOf('{', from);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        return code.substring(open);
    }

    /** A document with its {@code //} comments taken out, so an id a comment names counts as nothing. */
    @Nonnull
    private static String code(@Nonnull Path path) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int comment = line.indexOf("//");
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return out.toString();
    }
}
