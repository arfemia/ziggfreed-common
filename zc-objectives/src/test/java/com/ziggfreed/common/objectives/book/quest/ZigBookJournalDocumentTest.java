package com.ziggfreed.common.objectives.book.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.book.LedgerLayout;

/**
 * The journal tab's document and the Java that drives it, held together. A command against an id the document lacks
 * disconnects every player, so every id the tab's Java spells is declared in its own document, and the tab never
 * spells a kit template's child ids at all (the kit's painters own them). The document is the plan's: a toolbar of
 * four status segments, the category and tag dropdowns and the search row; the list column of sections; the page
 * card holding the kit's {@code @ZigDetailPage}; and the empty states, at the numbers {@link LedgerLayout} holds.
 */
class ZigBookJournalDocumentTest {

    private static final Path DOCUMENT = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages",
            "ZigBookJournal.ui");

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives",
            "book", "quest");

    /** The Java that addresses the document. */
    private static final List<String> DRIVERS = List.of("QuestJournalTab.java", "QuestJournalPlan.java");

    /** zc-presentation's shared documents, which the tab's document imports by path. */
    private static final Path KIT = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common");

    /** Child ids of the kit's templates: the painters spell these, never a page. */
    private static final Set<String> KIT_CHILD_IDS = Set.of("#Select", "#Rows", "#Head", "#More", "#Section",
            "#Title", "#Meta", "#State", "#Value", "#Mark", "#Accent", "#Pic", "#IcoTex", "#DTitle", "#DMeta",
            "#DToggle", "#DBlocks", "#DActions", "#Primary", "#Secondary", "#Danger", "#Hint", "#Lines", "#LineText",
            "#ETitle", "#ELine", "#EAction", "#EPic", "#Seg", "#Label", "#Check", "#Dot", "#SearchField",
            "#SearchBtn", "#ClearBtn");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?(?![0-9A-Za-z])");
    private static final Pattern REFERENCE = Pattern.compile("\\$([A-Za-z]+)\\.@([A-Za-z0-9]+)");
    private static final Pattern IMPORT = Pattern.compile("\\$([A-Za-z]+)\\s*=\\s*\"([^\"]+)\"\\s*;");

    @Test
    void everyIdTheTabAddressesIsDeclaredInItsOwnDocument() throws IOException {
        String ui = code(DOCUMENT);
        Set<String> spelled = idsSpelledBy(DRIVERS);
        assertFalse(spelled.isEmpty(), "the tab addresses its document");
        List<String> missing = new ArrayList<>();
        for (String id : spelled) {
            if (!declares(ui, id)) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing, "ids the tab's Java sets that its document does not declare");
    }

    @Test
    void theTabNeverSpellsAKitTemplatesChildIds() throws IOException {
        Set<String> spelled = idsSpelledBy(DRIVERS);
        for (String child : KIT_CHILD_IDS) {
            assertFalse(spelled.contains(child), "the tab spells " + child + ", which only a kit painter may");
        }
    }

    @Test
    void theTabSpellsNoColour() throws IOException {
        for (String driver : DRIVERS) {
            Path file = SOURCES.resolve(driver);
            String java = Files.readString(file, StandardCharsets.UTF_8);
            Matcher literal = STRING_LITERAL.matcher(java);
            while (literal.find()) {
                assertFalse(HEX.matcher(literal.group(1)).find(), driver + " pushes a colour: " + literal.group());
            }
        }
    }

    @Test
    void theDocumentHoldsTheBooksNumbers() throws IOException {
        String ui = code(DOCUMENT);
        assertTrue(own(block(ui, "Group #Journal")).contains("Anchor: (Height: " + LedgerLayout.BODY_HEIGHT + ");"),
                "the tab fills the shell's body");
        assertTrue(own(block(ui, "Group #Toolbar")).contains("Anchor: (Height: " + LedgerLayout.TOOLBAR_HEIGHT
                + ", Bottom: " + LedgerLayout.TOOLBAR_GAP + ");"), "the toolbar row and its gap");
        assertTrue(own(block(ui, "Group #Split")).contains("Anchor: (Height: " + LedgerLayout.SPLIT_HEIGHT + ");"),
                "the list-and-page split");
        assertTrue(own(block(ui, "Group #ListColumn")).contains("Anchor: (Width: " + LedgerLayout.LIST_WIDTH + ");"),
                "the list column");
        String list = own(block(ui, "Group #List"));
        assertTrue(list.contains("LayoutMode: TopScrolling;"), "the list scrolls");
        assertTrue(list.contains("Padding: (Right: " + LedgerLayout.SCROLL_GUTTER + ");"), "room for its scrollbar");
        assertTrue(own(block(ui, "Group #SplitGutter")).contains("Anchor: (Width: " + LedgerLayout.GUTTER + ");"));
        assertTrue(own(block(ui, "Group #PageCard")).contains("Anchor: (Width: " + LedgerLayout.PAGE_WIDTH + ");"),
                "the page card");
        assertEquals(LedgerLayout.INNER_WIDTH, LedgerLayout.LIST_WIDTH + LedgerLayout.GUTTER + LedgerLayout.PAGE_WIDTH);
    }

    @Test
    void theToolbarIsFourSegmentsTwoDropdownsAndTheSearchRow() throws IOException {
        String ui = code(DOCUMENT);
        for (String segment : List.of("#SegAll", "#SegProgress", "#SegAvailable", "#SegDone")) {
            assertTrue(declaresAs(ui, "$ZW.@ZigSegment", segment), segment + " is a kit segment");
        }
        assertTrue(declaresAs(ui, "$C.@DropdownBox", "#CategoryDropdown"));
        assertTrue(own(block(ui, "$C.@DropdownBox #CategoryDropdown")).contains("Width: 200"), "category 200");
        String tag = own(block(ui, "$C.@DropdownBox #TagDropdown"));
        assertTrue(tag.contains("Width: 200"), "tag 200");
        assertTrue(tag.contains("Visible: false;"), "the tag dropdown shows only when a quest carries a tag");
        String search = own(block(ui, "$S.@ZigSearchRow #Search"));
        assertTrue(search.contains("@Anchor = (Width: 300"), "the search row is 300 wide");
        assertTrue(search.contains("@Placeholder = %ziggfreedcommon.journal.search.placeholder;"),
                "its placeholder is a journal key (letters, digits and dots only)");
        int used = 4 * 132 + 12 + 200 + 12 + 200 + 300;
        assertTrue(used <= LedgerLayout.INNER_WIDTH, "the toolbar fits the body");
    }

    @Test
    void thePageAndTheEmptyStatesAreTheKitsPiecesSizedByTheirParents() throws IOException {
        String ui = code(DOCUMENT);
        assertTrue(ui.contains("$ZW = \"../Common/ZigKit.ui\";"), "the kit is imported as $ZW");
        assertTrue(declaresAs(ui, "$ZW.@ZigDetailPage", "#Page"));
        assertTrue(declaresAs(ui, "$ZW.@ZigEmptyState", "#ListEmpty"));
        assertTrue(declaresAs(ui, "$ZW.@ZigEmptyState", "#JournalEmpty"));
        for (String sized : List.of("$ZW.@ZigDetailPage #Page", "$ZW.@ZigEmptyState #ListEmpty",
                "$ZW.@ZigEmptyState #JournalEmpty")) {
            assertTrue(own(block(ui, sized)).contains("FlexWeight: 1;"), sized + " is sized by its parent");
        }
        for (String hidden : List.of("$ZW.@ZigEmptyState #ListEmpty", "$ZW.@ZigEmptyState #JournalEmpty")) {
            assertTrue(own(block(ui, hidden)).contains("Visible: false;"), hidden + " shows only when empty");
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
        assertTrue(checked > 0, "the tab uses the kit's documents");
    }

    @Test
    void sizesAreTypeStepsAndNoRetiredWidgetIsDeclared() throws IOException {
        String ui = code(DOCUMENT);
        assertFalse(Pattern.compile("FontSize:\\s*\\d").matcher(ui).find(), "every size comes from the type scale");
        assertFalse(ui.contains("ItemIcon"), "no ItemIcon: pictures are the kit's AssetImage slot");
        assertFalse(ui.contains("TextButton"), "a labelled button is a Button with its #Label");
        assertFalse(HEX.matcher(ui).find(), "every colour is a ZigTokens.ui token");
        assertFalse(Pattern.compile("#[A-Za-z0-9]*_").matcher(ui).find(), "an id is letters and digits only");
        assertFalse(Pattern.compile("(Vertical|Horizontal)Alignment:\\s*(?!Start|Center|End)").matcher(ui).find(),
                "alignments take Start, Center or End");
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
                Matcher id = ID.matcher(literal.group(1));
                while (id.find()) {
                    if (!HEX.matcher(id.group()).matches()) {
                        ids.add(id.group());
                    }
                }
            }
        }
        return ids;
    }

    /** Whether {@code ui} declares {@code id} on an element or a template instance. */
    private static boolean declares(@Nonnull String ui, @Nonnull String id) {
        return Pattern.compile("(?m)(^|\\s)(?:[A-Z][A-Za-z]*|\\$[A-Za-z]+\\.@[A-Za-z0-9]+|@[A-Za-z0-9]+)\\s+"
                + Pattern.quote(id) + "\\s*\\{").matcher(ui).find();
    }

    private static boolean declaresAs(@Nonnull String ui, @Nonnull String type, @Nonnull String id) {
        return Pattern.compile(Pattern.quote(type) + "\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(ui).find();
    }

    /** Java with its comments removed, so an id named in prose is not taken for one the tab sets. */
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
