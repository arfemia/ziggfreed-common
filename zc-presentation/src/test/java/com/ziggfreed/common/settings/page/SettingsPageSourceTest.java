package com.ziggfreed.common.settings.page;

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
