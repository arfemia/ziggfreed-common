package com.ziggfreed.common.settings.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The page answers the client on every path: a rail click is handled before the page reads its own
 * action (a rail click carries none), a click its plan cannot resolve is answered, every handler repaints
 * or answers, the toast host is appended, and every document it names ships. No test can stand the page
 * up (its base class's static logger needs the engine's log manager), so the source is read.
 */
class SettingsPageSourceTest {

    /** A source file of this package, its line endings read as LF whatever the checkout wrote. */
    @Nonnull
    private static String source(@Nonnull String file) throws IOException {
        return Files.readString(Path.of("src", "main", "java", "com", "ziggfreed", "common", "settings", "page", file),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void theRailIsAnsweredFirstAndEveryRefusalAnswers() throws IOException {
        String page = source("SettingsPage.java");

        assertTrue(page.contains("ZigMenu.paint("), "the page paints the rail");
        assertTrue(page.contains("MenuSlot.SETTINGS.id()"), "with the Settings tab selected");
        int handler = page.indexOf("public void handleDataEvent(");
        int rail = page.indexOf("rail.handle(", handler);
        int action = page.indexOf("data.action", handler);
        assertTrue(handler > 0 && rail > handler && rail < action,
                "a rail click carries no Action, so the rail answers it before the page reads one");
        assertTrue(page.contains("default -> answer();"), "an action the page does not know is answered");
        for (String name : List.of("onToggle", "onChoice", "onTile")) {
            int at = page.indexOf("private void " + name + "(");
            assertTrue(at > 0, "the page has " + name);
            String body = page.substring(at, page.indexOf("\n    }\n", at));
            assertTrue(body.contains("answer();"), name + " answers a click its plan cannot resolve");
        }
        assertTrue(page.contains("renderToastInto(cmd);"), "the toast host is appended on every build");
    }

    /**
     * The Settings tab declares the title row's branding hosts (M356), so it tells the painter so: the paint passes
     * {@code titleRow} true. The page writes its own title onto {@code #PanelTitle} BEFORE the paint, so a
     * right-mode server name the painter writes there wins (the last write is what the client shows), and it never
     * addresses the retired {@code #SettingsTitle}, which the document no longer declares.
     */
    @Test
    void theTitleIsWrittenBeforeTheRailSoARightModeServerNameWins() throws IOException {
        String page = source("SettingsPage.java");
        int build = page.indexOf("public void build(");
        assertTrue(build > 0, "the page has a build");
        int title = page.indexOf("cmd.set(\"#PanelTitle.TextSpans\"", build);
        int paint = page.indexOf("ZigMenu.paint(", build);
        assertTrue(title > 0, "the page writes its title onto #PanelTitle");
        assertTrue(paint > 0 && title < paint, "its own title first, then the painter's branding over it");
        String call = page.substring(paint, page.indexOf(';', paint)).replaceAll("\\s+", " ");
        assertTrue(call.endsWith(", true)"), "the paint is told the page has the title row: " + call);
        assertFalse(page.contains("#SettingsTitle"), "the retired id would disconnect the player");
        assertTrue(page.contains("cmd.set(\"#SettingsDescription.TextSpans\""), "the page's own description stays");
    }

    @Test
    void everyDocumentThePageNamesShips() {
        for (String doc : List.of(SettingsPage.PAGE_TEMPLATE, SettingsPage.SECTION_TEMPLATE, SettingsPage.TILE_TEMPLATE,
                SettingsPage.ROW_HEADING, SettingsPage.ROW_TOGGLE, SettingsPage.ROW_CHOICE)) {
            assertNotNull(SettingsPageSourceTest.class.getResource("/Common/UI/Custom/" + doc), doc + " ships");
        }
    }

    @Test
    void theEventShapeDeclaresTheRailAndTheLiveValue() throws IOException {
        String data = source("SettingsEventData.java");

        assertTrue(data.contains("ZigMenu.EVENT_KEY"), "a rail click's index rides the menu's key");
        assertTrue(data.contains("SettingsPage.VALUE_KEY"));
        assertTrue(SettingsPage.VALUE_KEY.startsWith("@"), "a live value is read only under an @ key");
    }
}
