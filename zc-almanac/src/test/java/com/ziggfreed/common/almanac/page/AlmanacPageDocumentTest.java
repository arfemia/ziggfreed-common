package com.ziggfreed.common.almanac.page;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.icon.IconRenderer;

/**
 * The page document, and the shared list row the page appends, against the elements the page paints
 * into. The page itself cannot run in a unit JVM (the engine's command builder reaches the item
 * store), and a command against an element the document lacks crashes the client, so each document
 * is held to the ids the Java addresses.
 */
class AlmanacPageDocumentTest {

    private static final Pattern SHIPS_HIDDEN = Pattern.compile("\\bVisible\\s*:\\s*false\\s*;");

    @Test
    void theSeasonsPictureHasTheSlotThePagePaintsItInto() throws IOException {
        String slot = block(document(AlmanacPage.PAGE_TEMPLATE), AlmanacPage.SEASON_ICON);

        assertNotNull(slot, AlmanacPage.PAGE_TEMPLATE + " declares no " + AlmanacPage.SEASON_ICON
                + ", which the page paints the season's picture into on every build");
        assertTrue(slot.contains("ItemGrid " + IconRenderer.ITEM_ICON_ID),
                "the slot holds the grid an item's picture is drawn in");
        assertTrue(slot.contains("AssetImage " + IconRenderer.TEXTURE_ICON_ID),
                "the slot holds the image a texture's picture is drawn in");
    }

    @Test
    void eachListedSeasonsRowHasAHiddenSlotThePagePaintsItsPictureInto() throws IOException {
        String slot = block(document(AlmanacPage.ROW_TEMPLATE), AlmanacPage.ROW_ICON);

        assertNotNull(slot, AlmanacPage.ROW_TEMPLATE + " declares no " + AlmanacPage.ROW_ICON
                + ", which the page paints each listed season's picture into");
        assertTrue(SHIPS_HIDDEN.matcher(ownProperties(slot)).find(),
                "the slot itself ships Visible: false, so a list that paints no picture, and a section "
                        + "heading, reads as it did before the slot existed");
        assertTrue(slot.contains("ItemGrid " + IconRenderer.ITEM_ICON_ID),
                "the slot holds the grid an item's picture is drawn in");
        assertTrue(slot.contains("AssetImage " + IconRenderer.TEXTURE_ICON_ID),
                "the slot holds the image a texture's picture is drawn in");
    }

    /** The shipped document with every {@code //} comment removed, so a sentence never satisfies a check. */
    @Nonnull
    private static String document(@Nonnull String template) throws IOException {
        try (InputStream in = AlmanacPageDocumentTest.class.getResourceAsStream("/Common/UI/Custom/" + template)) {
            assertNotNull(in, "the classpath ships " + template);
            StringBuilder out = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int at = line.indexOf("//");
                out.append(at < 0 ? line : line.substring(0, at)).append('\n');
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
