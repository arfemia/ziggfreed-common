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
 * The page document against the elements the page paints into. The page itself cannot run in a unit
 * JVM (the engine's command builder reaches the item store), and a command against an element the
 * document lacks crashes the client, so the document is held to the ids the Java addresses.
 */
class AlmanacPageDocumentTest {

    @Test
    void theSeasonsPictureHasTheSlotThePagePaintsItInto() throws IOException {
        String slot = block(document(), AlmanacPage.SEASON_ICON);

        assertNotNull(slot, AlmanacPage.PAGE_TEMPLATE + " declares no " + AlmanacPage.SEASON_ICON
                + ", which the page paints the season's picture into on every build");
        assertTrue(slot.contains("ItemGrid " + IconRenderer.ITEM_ICON_ID),
                "the slot holds the grid an item's picture is drawn in");
        assertTrue(slot.contains("AssetImage " + IconRenderer.TEXTURE_ICON_ID),
                "the slot holds the image a texture's picture is drawn in");
    }

    /** The shipped document with every {@code //} comment removed, so a sentence never satisfies a check. */
    @Nonnull
    private static String document() throws IOException {
        try (InputStream in = AlmanacPageDocumentTest.class.getResourceAsStream(
                "/Common/UI/Custom/" + AlmanacPage.PAGE_TEMPLATE)) {
            assertNotNull(in, "the module ships " + AlmanacPage.PAGE_TEMPLATE);
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
}
