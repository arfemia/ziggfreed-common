package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The records page on the shared menu's rail, opt in: with {@code LeaderboardPageDeps.menuTab} set the page
 * builds from its menu-frame document, which holds the same body as the standalone one (a board with no
 * tab, Kweebec's, keeps its own 860 x 700 frame), paints the rail, and answers a rail click before it reads
 * its own action. The body lives in two documents, so their element ids are held one for one: a command
 * against an element one of them lacks disconnects the player. The new document is held to the client
 * parser's id and brace rules too, since zc-presentation's syntax test sees only its own module.
 */
class LeaderboardMenuDocumentTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");
    private static final Path STANDALONE = PAGES.resolve("ZigLeaderboardPage.ui");
    private static final Path MENU = PAGES.resolve("ZigLeaderboardMenuPage.ui");
    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
            "leaderboard", "LeaderboardPage.java");

    /** What the client's parser accepts as an element id: a letter, then letters and digits. */
    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");

    /** Every {@code #...} token, however malformed, so one the {@link #ID} shape rejects is still seen. */
    private static final Pattern ID_TOKEN = Pattern.compile("#([A-Za-z][A-Za-z0-9_.\\-]*)");

    /** An element declaration: a type, then an id, then its block. */
    private static final Pattern DECLARED = Pattern.compile("\\b[A-Z]\\w*\\s+(#[A-Za-z][A-Za-z0-9]*)\\s*\\{");

    @Test
    void theMenuDocumentSitsInTheMenuFrameAndTheStandaloneOneKeepsItsOwn() throws IOException {
        String menu = code(MENU);
        assertTrue(menu.contains("$F.@ZigMenuFrame"), "the records page on the rail sits in the shared menu frame");
        assertFalse(menu.contains("@ZigDecoratedFrame"), "and nowhere else");

        String standalone = code(STANDALONE);
        assertTrue(standalone.contains("$F.@ZigDecoratedFrame"), "a board with no tab keeps its own frame");
        assertTrue(standalone.contains("Anchor: (Width: 860, Height: 700)"), "at its own size");
        assertFalse(standalone.contains("@ZigMenuFrame"));
    }

    @Test
    void bothDocumentsDeclareTheSameElementsOneForOne() throws IOException {
        List<String> standalone = declared(code(STANDALONE));
        assertTrue(standalone.size() > 30 && standalone.contains("#LeaderboardList") && standalone.contains("#YourRank"),
                "the scan found almost nothing, so it is not scanning: " + standalone);
        assertEquals(standalone, declared(code(MENU)),
                "the page addresses the same ids whichever document it built from");
    }

    @Test
    void theMenuDocumentPassesTheParsersIdAndBraceRules() throws IOException {
        String menu = code(MENU);
        Matcher m = ID_TOKEN.matcher(menu);
        while (m.find()) {
            String head = m.group(1).split("\\.")[0];
            assertTrue(ID.matcher("#" + head).matches(), "#" + head + " is not a letter followed by letters and digits");
        }
        int depth = 0;
        for (char c : menu.toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            assertTrue(depth >= 0, "the menu document closes a brace it never opened");
        }
        assertEquals(0, depth, "the menu document leaves a brace open");
    }

    @Test
    void thePagePaintsTheRailAndAnswersItBeforeItsAction() throws IOException {
        String page = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertTrue(page.contains("ZigMenu.paint("), "the page paints the rail when its deps name a tab");
        int handler = page.indexOf("public void handleDataEvent(");
        int rail = page.indexOf("rail.handle(", handler);
        int action = page.indexOf("data.action", handler);
        assertTrue(handler > 0 && rail > handler && rail < action,
                "a rail click carries no Action, and the page closes on a missing one");
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

    /** Every element id a document declares, in document order. */
    @Nonnull
    private static List<String> declared(@Nonnull String ui) {
        List<String> out = new ArrayList<>();
        Matcher m = DECLARED.matcher(ui);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
