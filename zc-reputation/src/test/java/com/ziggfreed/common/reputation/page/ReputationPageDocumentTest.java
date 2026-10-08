package com.ziggfreed.common.reputation.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * The page document against the page: it sits in the shared menu frame, declares every element the page
 * paints (a command against a missing element disconnects the player), names each element as a letter
 * then letters or digits (an underscore makes the client refuse the document), and the page answers a
 * rail click before it reads its own action. The page itself cannot run, or even load, in a unit JVM (its
 * engine superclass's static init reaches the engine logger), so the test reads only its constants and
 * {@link ReputationPageLayout}.
 */
class ReputationPageDocumentTest {

    private static final Pattern ELEMENT = Pattern.compile(
            "\\b(?:Group|Label|ItemGrid|AssetImage|Button)\\s+#([A-Za-z0-9_]+)");

    @Test
    void theDocumentSitsInTheSharedMenuAndDeclaresEveryElementThePagePaints() throws IOException {
        String ui = document(ReputationPage.PAGE_TEMPLATE);
        assertTrue(ui.contains("$F.@ZigMenuFrame"), "the page carries the shared rail");
        assertFalse(ui.contains("@ZigDecoratedFrame"));
        for (String id : ReputationPageLayout.PAINTED_IDS) {
            assertTrue(ui.contains(id + " {"), ReputationPage.PAGE_TEMPLATE + " declares no " + id);
        }
    }

    /** The list and the reading page are the kit's, so the painters find every id they address inside them. */
    @Test
    void theReadingPageAndItsEmptyStateAreTheKitsOwnTemplates() throws IOException {
        String ui = document(ReputationPage.PAGE_TEMPLATE);
        assertTrue(ui.contains("$ZW = \"../Common/ZigKit.ui\""), "the page imports the kit");
        assertTrue(ui.contains("$ZW.@ZigDetailPage " + ReputationPage.DETAIL + " {"), "the reading page is the kit's");
        assertTrue(ui.contains("$ZW.@ZigEmptyState " + ReputationPage.EMPTY + " {"), "so is its empty state");
        assertTrue(ui.contains("Group " + ReputationPage.LIST + " {"), "the list is a plain group the painter fills");
    }

    @Test
    void everyElementIdIsALetterThenLettersOrDigits() throws IOException {
        Matcher element = ELEMENT.matcher(document(ReputationPage.PAGE_TEMPLATE));
        while (element.find()) {
            assertTrue(element.group(1).matches("[A-Za-z][A-Za-z0-9]*"), "#" + element.group(1));
        }
    }

    @Test
    void thePageAnswersTheRailBeforeItsOwnAction() throws IOException {
        String page = Files.readString(Path.of("src", "main", "java", "com", "ziggfreed", "common", "reputation",
                "page", "ReputationPage.java"), StandardCharsets.UTF_8);
        assertTrue(page.contains("ZigMenu.paint("), "the page paints the rail");
        assertTrue(page.contains("MenuSlot.REPUTATION.id()"), "with the Reputation tab selected");
        int handler = page.indexOf("public void handleDataEvent(");
        int rail = page.indexOf("rail.handle(", handler);
        int action = page.indexOf("data.action", handler);
        assertTrue(handler > 0 && rail > handler && rail < action,
                "a rail click carries no Action, and the page closes on anything it does not know");
    }

    /**
     * The list column is the page's one fixed width, and the page derives it from the frame's body width
     * (a document cannot read a Java constant, so the two are held together here); the detail column flexes
     * into the rest, so a change to the frame never pushes the page past it.
     */
    @Test
    void theListColumnIsThePagesOwnShareOfTheMenuBodyAndTheDetailTakesTheRest() throws IOException {
        String ui = document(ReputationPage.PAGE_TEMPLATE);
        String list = block(ui, "#LeftPanel");
        String detail = block(ui, "#RightPanel");
        assertNotNull(list, "the page declares its list column");
        assertNotNull(detail, "the page declares its detail column");
        assertTrue(ownProperties(list).contains("Width: " + ReputationPageLayout.LIST_WIDTH),
                "the list column is LIST_WIDTH wide, the share it takes of MenuFrame.BODY_WIDTH");
        assertTrue(ReputationPageLayout.LIST_WIDTH > 0 && ReputationPageLayout.LIST_WIDTH < MenuFrame.BODY_WIDTH,
                "the list leaves the detail column room inside the menu body");
        assertTrue(ownProperties(detail).contains("FlexWeight: 1"), "the detail column takes what the list leaves");
    }

    /** The shipped document with every {@code //} comment removed, so a sentence never satisfies a check. */
    @Nonnull
    private static String document(@Nonnull String template) throws IOException {
        try (InputStream in = ReputationPageDocumentTest.class.getResourceAsStream("/Common/UI/Custom/" + template)) {
            assertNotNull(in, "the classpath ships " + template);
            StringBuilder out = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                int comment = line.indexOf("//");
                out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
            }
            return out.toString();
        }
    }

    /** The block the element {@code id} declares, from its opening brace to the one that closes it; null for none. */
    @Nullable
    private static String block(@Nonnull String ui, @Nonnull String id) {
        Matcher declaration = Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(ui);
        if (!declaration.find()) {
            return null;
        }
        int depth = 0;
        for (int i = declaration.end() - 1; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return ui.substring(declaration.start(), i + 1);
            }
        }
        return null;
    }

    /** What a block says about its own element: its text inside its braces, minus every block nested in it. */
    @Nonnull
    private static String ownProperties(@Nonnull String block) {
        StringBuilder own = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
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
