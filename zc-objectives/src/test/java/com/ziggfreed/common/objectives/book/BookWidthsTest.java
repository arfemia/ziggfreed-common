package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * The book's chips-or-dropdown rule measures the strip the frame actually leaves it: the menu's body
 * width (from the frame's one rail constant) less the right panel's padding and, where it shows, the side
 * column. The two numbers the book adds are its document's own, so a layout change cannot leave the rule
 * measuring a strip that is no longer there.
 */
class BookWidthsTest {

    @Test
    void theStripIsTheMenuBodyLessThePaddingAndTheSideColumn() {
        assertEquals(MenuFrame.BODY_WIDTH - BookWidths.RIGHT_PANEL_PADDING - BookWidths.SIDE_PANEL_WIDTH,
                BookWidths.stripWidthBudget(true));
        assertEquals(MenuFrame.BODY_WIDTH - BookWidths.RIGHT_PANEL_PADDING, BookWidths.stripWidthBudget(false));
    }

    @Test
    void chipsFitOnlyWhileEveryChipAndAllFitOnOneLine() {
        int budget = BookWidths.stripWidthBudget(true);
        int fits = budget / BookWidths.CAT_TAB_OUTER_WIDTH - 1;
        assertTrue(BookWidths.categoryChipsFit(fits, BookWidths.CAT_TAB_OUTER_WIDTH, budget));
        assertFalse(BookWidths.categoryChipsFit(fits + 1, BookWidths.CAT_TAB_OUTER_WIDTH, budget),
                "one chip more than fits falls to the dropdown");
    }

    @Test
    void theBooksOwnWidthsAreItsDocuments() throws IOException {
        String ui;
        try (InputStream in = BookWidthsTest.class.getResourceAsStream("/Common/UI/Custom/Pages/ZigObjectiveBookPage.ui")) {
            assertNotNull(in);
            ui = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int side = ui.indexOf("Group #SidePanel {");
        int right = ui.indexOf("Group #RightPanel {");
        assertTrue(side > 0 && right > 0, "the book declares both columns");
        assertTrue(ui.substring(side, side + 120).contains("Width: " + BookWidths.SIDE_PANEL_WIDTH));
        assertTrue(ui.substring(right, right + 120).contains("Padding: (Full: " + BookWidths.RIGHT_PANEL_PADDING / 2 + ")"));
    }
}
