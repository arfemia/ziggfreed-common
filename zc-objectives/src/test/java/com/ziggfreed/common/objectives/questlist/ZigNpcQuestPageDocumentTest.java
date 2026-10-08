package com.ziggfreed.common.objectives.questlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The NPC quest page's document and the Java that drives it, held together: a command against an id the document
 * lacks disconnects the player, so every id the page's Java spells must be declared in its own document (or be the
 * frame's), and the page never spells a kit template's child ids at all, since the kit's painters own those. The
 * document itself is the kit's: two {@code @ZigSegment}s, a list the ledger painter fills, the book's
 * {@code @ZigDetailPage}, and an {@code @ZigEmptyState}, inside the frame it has always had.
 */
class ZigNpcQuestPageDocumentTest {

    private static final Path DOCUMENT = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages",
            "ZigNpcQuestPage.ui");

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives",
            "questlist");

    /** The Java that addresses the document: the page, and the pure plan that names its ids. */
    private static final List<String> DRIVERS = List.of("ZigNpcQuestPage.java", "NpcQuestPagePlan.java");

    /** zc-presentation's shared documents, which the page imports by path. */
    private static final Path KIT = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?(?![0-9A-Za-z])");
    private static final Pattern REFERENCE = Pattern.compile("\\$([A-Za-z]+)\\.@([A-Za-z0-9]+)");
    private static final Pattern IMPORT = Pattern.compile("\\$([A-Za-z]+)\\s*=\\s*\"([^\"]+)\"\\s*;");

    @Test
    void everyIdThePageAddressesIsDeclaredInItsOwnDocument() throws IOException {
        String ui = code(DOCUMENT);
        String frames = code(KIT.resolve("ZigFrames.ui"));
        Set<String> spelled = idsSpelledBy(DRIVERS);
        assertFalse(spelled.isEmpty(), "the page addresses its document");
        List<String> missing = new ArrayList<>();
        for (String id : spelled) {
            boolean declared = declares(ui, id) || declares(block(frames, "@ZigDecoratedFrame = Group"), id);
            if (!declared) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing, "ids the page's Java sets that its document does not declare; a template's "
                + "child ids are the kit painters' to spell, never the page's");
    }

    @Test
    void thePageSpellsNoColour() throws IOException {
        for (String driver : DRIVERS) {
            Path file = SOURCES.resolve(driver);
            if (!Files.exists(file)) {
                continue;
            }
            String java = Files.readString(file, StandardCharsets.UTF_8);
            Matcher literal = STRING_LITERAL.matcher(java);
            while (literal.find()) {
                assertFalse(HEX.matcher(literal.group(1)).find(),
                        driver + " pushes a colour (" + literal.group() + "): a row, segment, button or state word "
                                + "takes its look from the kit's named styles");
            }
        }
    }

    /**
     * The list column is 450, wide enough that a row's title reads on one line beside its pin and its meta line
     * reads whole beside the trail; the frame grew by the same 110, so the reading page on the right keeps the
     * 710 it is laid out for.
     */
    @Test
    void theFrameAndTheListKeepTheirSizes() throws IOException {
        String ui = code(DOCUMENT);
        assertTrue(own(block(ui, "$F.@ZigDecoratedFrame")).contains("Anchor: (Width: 1160, Height: 850);"),
                "the frame is 1160 x 850");
        assertTrue(own(block(ui, "Group #LeftPanel")).contains("Anchor: (Width: 450);"), "the list column is 450");
        assertTrue(own(block(ui, "Group #RightPanel")).contains("FlexWeight: 1;"),
                "the reading page takes what the list leaves: 1160 - 450 = 710, the width it is laid out for");
        assertTrue(own(block(ui, "Group #LeftPanel"))
                        .contains("Background: (TexturePath: \"../Common/ContainerPanelPatch.png\", Border: 4);"),
                "a consumer's theme retints #LeftPanel's patch, so the panel keeps one");
    }

    @Test
    void theListAndThePageAreTheKitsPieces() throws IOException {
        String ui = code(DOCUMENT);
        assertTrue(ui.contains("$ZW = \"../Common/ZigKit.ui\";"), "the kit is imported as $ZW");
        assertTrue(declaresAs(ui, "$ZW.@ZigSegment", "#TabHere"), "Here is a segment");
        assertTrue(declaresAs(ui, "$ZW.@ZigSegment", "#TabMine"), "Mine is a segment");
        assertTrue(declaresAs(ui, "Group", "#QuestList"), "the list the ledger painter fills");
        assertTrue(own(block(ui, "Group #QuestList")).contains("LayoutMode: TopScrolling;"), "the list scrolls");
        assertTrue(declaresAs(ui, "$ZW.@ZigDetailPage", "#Page"), "the book's quest page");
        assertTrue(declaresAs(ui, "$ZW.@ZigEmptyState", "#PageEmpty"), "the empty state");
        for (String sized : List.of("$ZW.@ZigDetailPage #Page", "$ZW.@ZigEmptyState #PageEmpty")) {
            assertTrue(own(block(ui, sized)).contains("FlexWeight: 1;"), sized + " is sized by its parent");
        }
        assertTrue(own(block(ui, "$ZW.@ZigEmptyState #PageEmpty")).contains("Visible: false;"),
                "the empty state shows only when a list is empty");
        String kit = code(KIT.resolve("ZigKit.ui"));
        for (String template : List.of("@ZigSegment = Button", "@ZigDetailPage = Group", "@ZigEmptyState = Group")) {
            assertTrue(kit.contains(template + " {"), "the kit defines " + template);
        }
    }

    @Test
    void everyKitReferenceResolves() throws IOException {
        String ui = code(DOCUMENT);
        Matcher imports = IMPORT.matcher(ui);
        Map<String, String> aliases = new HashMap<>();
        while (imports.find()) {
            aliases.put(imports.group(1), imports.group(2));
        }
        Matcher ref = REFERENCE.matcher(ui);
        int checked = 0;
        while (ref.find()) {
            String alias = ref.group(1);
            String path = aliases.get(alias);
            assertTrue(path != null, "$" + alias + " is imported");
            if (!path.startsWith("../Common/")) {
                continue; // vanilla's Common.ui is not in this repository
            }
            String target = code(KIT.resolve(path.substring("../Common/".length())));
            assertTrue(Pattern.compile("(?m)^@" + ref.group(2) + "\\s*=").matcher(target).find(),
                    path + " defines @" + ref.group(2));
            checked++;
        }
        assertTrue(checked > 0, "the page uses the kit's documents");
    }

    @Test
    void sizesAreTypeStepsAndNoRetiredWidgetIsDeclared() throws IOException {
        String ui = code(DOCUMENT);
        assertFalse(Pattern.compile("FontSize:\\s*\\d").matcher(ui).find(), "every size comes from the type scale");
        assertFalse(ui.contains("ItemIcon"), "no ItemIcon: pictures are the kit's AssetImage slot");
        assertFalse(ui.contains("TextButton"), "a labelled button is a Button with its #Label");
        assertFalse(HEX.matcher(ui).find(), "every colour is a ZigTokens.ui token");
        for (String retired : List.of("#AcceptBtn", "#TurnInBtn", "#ClaimBtn", "#TrackBtn", "#AbandonBtn",
                "#ObjectivesSection", "#RewardsList", "#RequirementsSection", "#EmptyListLabel", "#DetailTitle")) {
            assertFalse(ui.contains(retired + " "), retired + " gave way to the book's page and action bar");
        }
    }

    // ==================== reading ====================

    /** Every {@code #Id} inside a string literal of the named sources (colours aside). */
    @Nonnull
    private static Set<String> idsSpelledBy(@Nonnull List<String> drivers) throws IOException {
        Set<String> ids = new TreeSet<>();
        for (String driver : drivers) {
            Path file = SOURCES.resolve(driver);
            if (!Files.exists(file)) {
                continue;
            }
            Matcher literal = STRING_LITERAL.matcher(stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            while (literal.find()) {
                String text = literal.group(1);
                Matcher id = ID.matcher(text);
                while (id.find()) {
                    String token = id.group();
                    if (HEX.matcher(token).matches()) {
                        continue;
                    }
                    ids.add(token);
                }
            }
        }
        return new LinkedHashSet<>(ids);
    }

    /** Whether {@code ui} declares {@code id} on an element or a template instance. */
    private static boolean declares(@Nonnull String ui, @Nonnull String id) {
        return Pattern.compile("(?m)(^|\\s)(?:[A-Z][A-Za-z]*|\\$[A-Za-z]+\\.@[A-Za-z0-9]+|@[A-Za-z0-9]+)\\s+"
                + Pattern.quote(id) + "\\s*\\{").matcher(ui).find()
                || Pattern.compile("(?m)^\\s*" + Pattern.quote(id) + "\\s*\\{").matcher(ui).find();
    }

    private static boolean declaresAs(@Nonnull String ui, @Nonnull String type, @Nonnull String id) {
        return Pattern.compile(Pattern.quote(type) + "\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(ui).find();
    }

    /** Java with its comments removed, so an id named in prose is not taken for one the page sets. */
    @Nonnull
    private static String stripComments(@Nonnull String java) {
        String noBlocks = java.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder out = new StringBuilder();
        for (String line : noBlocks.split("\n", -1)) {
            int at = lineCommentStart(line);
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }

    /** Where a {@code //} comment starts outside a string literal, or -1. */
    private static int lineCommentStart(@Nonnull String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '\\' && inString) {
                i++;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '/' && line.charAt(i + 1) == '/') {
                return i;
            }
        }
        return -1;
    }

    /** A document with every {@code //} comment removed. */
    @Nonnull
    private static String code(@Nonnull Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }

    /** The block {@code declaration} opens. */
    @Nonnull
    private static String block(@Nonnull String ui, @Nonnull String declaration) {
        Matcher m = Pattern.compile(Pattern.quote(declaration) + "\\s*\\{").matcher(ui);
        assertTrue(m.find(), "declares " + declaration);
        int depth = 0;
        for (int i = m.end() - 1; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return ui.substring(m.end() - 1, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces in " + declaration);
    }

    /** A block's own properties, without the blocks nested in it. */
    @Nonnull
    private static String own(@Nonnull String block) {
        StringBuilder own = new StringBuilder();
        int depth = 0;
        for (char c : block.toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 1) {
                own.append(c);
            }
        }
        return own.toString();
    }
}
