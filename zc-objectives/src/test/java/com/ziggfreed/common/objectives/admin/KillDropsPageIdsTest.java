package com.ziggfreed.common.objectives.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every id the Kill Drops page names in its own document is declared there, once: a command on an id the page lacks
 * disconnects the player, and a second declaration makes which one a command lands on the client's choice. The ids
 * inside the rows it appends are their painters' and the settings form's ({@code KitSelectorsTest} walks the kit's).
 * The page's constants are compile-time strings, so nothing here builds the page.
 */
class KillDropsPageIdsTest {

    private static final Path DOCUMENT = Path.of("src", "main", "resources", "Common", "UI", "Custom")
            .resolve(KillDropsAdminPage.PAGE_TEMPLATE);

    private static final List<String> IDS = List.of(KillDropsAdminPage.FIELDS, KillDropsAdminPage.STATUS,
            KillDropsAdminPage.EMPTY, KillDropsAdminPage.PANEL, KillDropsAdminPage.BACK_BUTTON,
            KillDropsAdminPage.SAVE_BUTTON, KillDropsAdminPage.PREV_BUTTON, KillDropsAdminPage.NEXT_BUTTON,
            KillDropsAdminPage.PAGE_LABEL, KillDropsAdminPage.PAGER);

    @Test
    void everyIdThePageNamesIsDeclaredOnceInItsDocument() throws IOException {
        assertTrue(Files.isRegularFile(DOCUMENT), "missing " + DOCUMENT.toAbsolutePath());
        String document = withoutComments(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
        List<String> wrong = new ArrayList<>();
        for (String id : IDS) {
            // A declaration: the id after an element type or a template, opening its block.
            Pattern declared = Pattern.compile("[A-Za-z@.$]+\\s+" + Pattern.quote(id) + "\\s*\\{");
            long count = declared.matcher(document).results().count();
            if (count != 1) {
                wrong.add(id + " declared " + count + " times");
            }
        }
        assertEquals(List.of(), wrong, "the page names only ids its document declares, each once");
    }

    /** The document with its // comments cut, so an id a comment mentions never reads as declared. */
    private static String withoutComments(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }
}
