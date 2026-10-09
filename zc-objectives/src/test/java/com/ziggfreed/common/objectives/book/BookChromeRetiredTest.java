package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The book's strip, rail seam and narrow theme seam are gone, and the book answers a rail click before it
 * reads its own action. A command against an element the document no longer has disconnects the player,
 * so the deleted ids may not survive in the page or its document; comments are read too, so the rewritten
 * javadoc names none of them. The redesign retired the rest of the old seams once nothing called them: the
 * side column painter and its chrome, the pre-redesign whole-state constructor, and the tracked-quests side
 * panel's renderer and row (tracked quests show on the book's own Quests tab and the tracker HUD).
 */
class BookChromeRetiredTest {

    private static final Path BOOK = Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives", "book");
    private static final Path UI = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages",
            "ZigObjectiveBookPage.ui");
    private static final List<String> GONE_IDS = List.of("#LeftPanel", "#TabBar", "#TabQuests", "#TabAchievements",
            "#RailContent", "#BrandingContainerLeft");

    @Test
    void thePageNamesNoStripRailOrWidthOfItsOwn() throws IOException {
        String page = Files.readString(BOOK.resolve("ObjectiveBookPage.java"), StandardCharsets.UTF_8);
        for (String gone : GONE_IDS) {
            assertFalse(page.contains(gone), "ObjectiveBookPage still names " + gone);
        }
        for (String gone : List.of("railPainter", "railPainted", "styleTab", "bindTab", "RAIL_WIDTH", "FRAME_INNER_WIDTH",
                "TAB_ACTIVE_TINT", "appendTemplate")) {
            assertFalse(page.contains(gone), "ObjectiveBookPage still carries " + gone);
        }
        String deps = Files.readString(BOOK.resolve("ObjectiveBookDeps.java"), StandardCharsets.UTF_8);
        for (String gone : List.of("railPainter", "LEGACY_THEME", "PageTheme", "sidePanelPainter", "ChromePainter",
                "NO_CHROME", "class Chrome", "paintGuarded", "bindExt", "ExtBinder")) {
            assertFalse(deps.contains(gone), "ObjectiveBookDeps still carries " + gone);
        }
        assertFalse(page.contains("String filterSubcategory"),
                "the pre-redesign whole-state constructor is gone; ObjectiveBookPages.open opens on a row");
        String pages = Files.readString(BOOK.resolve("ObjectiveBookPages.java"), StandardCharsets.UTF_8);
        for (String gone : List.of("resolvedTheme", "PageTheme")) {
            assertFalse(pages.contains(gone), "ObjectiveBookPages still carries " + gone);
        }
    }

    @Test
    void theTrackedQuestsSidePanelIsGone() {
        Path objectives = Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives");
        assertFalse(Files.exists(objectives.resolve("hud").resolve("TrackedQuestPanelRenderer.java")),
                "tracked quests show in the book and on the tracker HUD, never a side panel");
        assertFalse(Files.exists(UI.resolveSibling("ZigTrackedQuestRow.ui")), "its row went with it");
    }

    @Test
    void theDocumentSitsInTheMenuFrame() throws IOException {
        StringBuilder code = new StringBuilder();
        for (String line : Files.readAllLines(UI, StandardCharsets.UTF_8)) {
            int at = line.indexOf("//");
            code.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        String ui = code.toString();
        assertTrue(ui.contains("$F.@ZigMenuFrame"), "the book sits in the shared menu frame");
        assertFalse(ui.contains("@ZigDecoratedFrame"), "and nowhere else");
        for (String gone : GONE_IDS) {
            assertFalse(ui.contains(gone), "ZigObjectiveBookPage.ui still declares " + gone);
        }
    }

    @Test
    void aRailClickIsAnsweredBeforeTheBookReadsItsAction() throws IOException {
        String page = Files.readString(BOOK.resolve("ObjectiveBookPage.java"), StandardCharsets.UTF_8);
        assertTrue(page.contains("ZigMenu.paint("), "the book paints the shared rail");
        int handler = page.indexOf("public void handleDataEvent(");
        int rail = page.indexOf("rail.handle(", handler);
        int action = page.indexOf("data.action", handler);
        assertTrue(handler > 0 && rail > handler && rail < action,
                "a rail click carries no Action, and the book closes on an empty one");
    }
}
