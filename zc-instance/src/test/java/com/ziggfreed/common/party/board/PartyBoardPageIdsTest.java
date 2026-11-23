package com.ziggfreed.common.party.board;

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
 * Every id the party board's page names in its own document is declared there, once: a command on an id the page
 * lacks disconnects the player, and a second declaration makes which one a command lands on the client's choice. The
 * ids inside the kit templates it appends are their painters' ({@code KitSelectorsTest} walks those).
 */
class PartyBoardPageIdsTest {

    private static final Path DOCUMENT = Path.of("src", "main", "resources", "Common", "UI", "Custom")
            .resolve(PartyBoardIds.PAGE_TEMPLATE);

    @Test
    void everyIdThePageNamesIsDeclaredOnceInItsDocument() throws IOException {
        assertTrue(Files.isRegularFile(DOCUMENT), "missing " + DOCUMENT.toAbsolutePath());
        String document = withoutComments(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
        List<String> wrong = new ArrayList<>();
        for (String id : PartyBoardIds.ALL) {
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
