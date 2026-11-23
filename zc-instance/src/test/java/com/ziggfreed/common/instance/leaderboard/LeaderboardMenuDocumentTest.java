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
 * tab, Kweebec's, keeps its own 1200 x 800 box on the band, which with the band's head and foot fits a 1080 screen),
 * paints the rail, and answers a rail click before it reads its own action. The body lives in two documents, so their
 * element ids are held one for one: a command against an element one of them lacks disconnects the player. The new
 * document is held to the client parser's id and brace rules too, since zc-presentation's syntax test sees only its
 * own module. And every id the page's Java addresses by a literal is one its documents, its two row templates or the
 * frames declare; the kit's painters own their templates' ids and are held by zc-presentation's selector test.
 */
class LeaderboardMenuDocumentTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");
    private static final Path STANDALONE = PAGES.resolve("ZigLeaderboardPage.ui");
    private static final Path MENU = PAGES.resolve("ZigLeaderboardMenuPage.ui");
    private static final Path ROW = PAGES.resolve("ZigLeaderboardRow.ui");
    private static final Path STATS_ROW = PAGES.resolve("ZigLeaderboardStatsRow.ui");
    private static final Path FRAMES = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common", "ZigFrames.ui");
    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
            "leaderboard", "LeaderboardPage.java");

    /** How far the band reaches past a framed page's box, above and below together (its head and foot, 78 each). */
    private static final int BAND_HEAD_AND_FOOT = 156;

    /** The screen height every framed page fits with its band. */
    private static final int SCREEN_HEIGHT = 1080;

    /** The standalone frame's own size on its anchor. */
    private static final Pattern FRAME_SIZE = Pattern.compile("Anchor: \\(Width: (\\d+), Height: (\\d+)\\)");

    /** A Java string literal on one line. */
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    /** A Java comment: a block comment (javadoc included) or a line comment. */
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

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
        assertTrue(standalone.contains("Anchor: (Width: 1200, Height: 800)"), "at its own size");
        assertFalse(standalone.contains("@ZigMenuFrame"));

        Matcher size = FRAME_SIZE.matcher(standalone);
        assertTrue(size.find(), "the standalone frame names its size");
        assertTrue(Integer.parseInt(size.group(2)) + BAND_HEAD_AND_FOOT <= SCREEN_HEIGHT,
                "the band's head and foot reach past the box, and the whole band fits a 1080 screen");
    }

    @Test
    void everyIdThePageAddressesIsDeclaredByItsDocumentsItsRowsOrTheFrames() throws IOException {
        List<String> declared = new ArrayList<>();
        declared.addAll(declared(code(STANDALONE)));
        declared.addAll(declared(code(ROW)));
        declared.addAll(declared(code(STATS_ROW)));
        declared.addAll(declared(code(FRAMES)));
        assertTrue(declared.contains("#YouFigures") && declared.contains("#Edge") && declared.contains("#CloseButton"),
                "the scan found too little, so it is not scanning: " + declared);

        String java = COMMENT.matcher(Files.readString(PAGE, StandardCharsets.UTF_8)).replaceAll(" ");
        List<String> addressed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Matcher literal = LITERAL.matcher(java);
        while (literal.find()) {
            String text = literal.group();
            Matcher id = ID.matcher(text);
            while (id.find()) {
                String name = "#" + id.group(1);
                addressed.add(name);
                // A literal that ends on the id ("#Col" + c) names a numbered family: one of them must be declared.
                boolean numbered = id.end() == text.length() - 1;
                if (!declared.contains(name) && !(numbered && declaresNumbered(declared, name))) {
                    missing.add(name);
                }
            }
        }
        assertTrue(addressed.contains("#YouFigures") && addressed.contains("#Edge") && addressed.contains("#Medal"),
                "the page's literals were not found, so it is not scanning: " + addressed);
        assertTrue(missing.isEmpty(), "the records page addresses ids no document of its declares: " + missing);
    }

    /** Whether {@code declared} holds {@code prefix} followed by a number ({@code #Col0} for {@code #Col}). */
    private static boolean declaresNumbered(@Nonnull List<String> declared, @Nonnull String prefix) {
        for (String id : declared) {
            if (id.startsWith(prefix) && id.length() > prefix.length()
                    && id.substring(prefix.length()).chars().allMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
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
