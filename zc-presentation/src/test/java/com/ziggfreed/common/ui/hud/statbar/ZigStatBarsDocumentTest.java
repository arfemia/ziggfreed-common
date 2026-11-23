package com.ziggfreed.common.ui.hud.statbar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * {@code Hud/ZigStatBars.ui} declares every id {@link StatBarHud} pushes to (a push to an id the document lacks
 * disconnects the player) and the numbers {@link StatBarLayout} mirrors (a fill sized from the wrong ones overflows its
 * track): the slots, each slot's spacer, bar, name, track, fill and figure, a fill with a Background to retint, and
 * the bar's padding, the name's and the figure's widths, the gaps and the track's padding.
 */
class ZigStatBarsDocumentTest {

    @Test
    void theDocumentDeclaresWhatThePaintAddressesAndTheGeometryItMirrors() throws IOException {
        String ui = document();
        for (int i = 0; i < StatBarLayout.SLOTS; i++) {
            assertTrue(ui.contains("@StatBarSlot " + StatBarHud.SLOT_PREFIX + i + " "),
                    "the document declares slot " + i);
        }
        assertFalse(ui.contains(StatBarHud.SLOT_PREFIX + StatBarLayout.SLOTS + " "),
                "the document declares no slot past StatBarLayout.SLOTS");
        assertTrue(ui.contains("Group " + StatBarHud.ROOT + " "), "the document declares the wrapper");

        String slot = template(ui, "@StatBarSlot");
        for (String id : new String[] {"#Lead", "#Bar", "#Label", "#Track", "#Fill", "#Figure"}) {
            assertTrue(Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(slot).find(),
                    "a slot declares " + id);
        }
        assertTrue(Pattern.compile("Group #Fill\\s*\\{[^{}]*Background:").matcher(slot).find(),
                "the fill declares a Background, so its colour can be pushed");
        assertTrue(slot.contains("Padding: (Horizontal: " + StatBarLayout.PAD_X + ", Vertical: "),
                "the bar's end padding is StatBarLayout.PAD_X");
        assertTrue(slot.contains("Height: " + StatBarLayout.BAR_HEIGHT + ")"), "the bar is StatBarLayout.BAR_HEIGHT tall");
        assertTrue(slot.contains("Label #Label { Text: \"\"; Anchor: (Width: " + StatBarLayout.LABEL_WIDTH + ","),
                "the name's box is StatBarLayout.LABEL_WIDTH wide");
        assertTrue(slot.contains("Label #Figure { Text: \"\"; Anchor: (Width: " + StatBarLayout.FIGURE_WIDTH + ","),
                "the figure's box is StatBarLayout.FIGURE_WIDTH wide");
        Matcher gaps = Pattern.compile("Group \\{ Anchor: \\(Width: " + StatBarLayout.TRACK_GAP + "\\); \\}").matcher(slot);
        int count = 0;
        while (gaps.find()) {
            count++;
        }
        assertTrue(count == 2, "a gap of StatBarLayout.TRACK_GAP on each side of the track, found " + count);
        assertTrue(slot.contains("Padding: (Full: " + StatBarLayout.TRACK_PAD + ");"),
                "the track's padding is StatBarLayout.TRACK_PAD");
    }

    /** The shipped document with its line comments stripped. */
    @Nonnull
    private static String document() throws IOException {
        try (InputStream in = ZigStatBarsDocumentTest.class.getResourceAsStream(
                "/Common/UI/Custom/" + StatBarHud.DOCUMENT)) {
            assertNotNull(in, "the classpath ships " + StatBarHud.DOCUMENT);
            StringBuilder out = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int at = line.indexOf("//");
                out.append(at < 0 ? line : line.substring(0, at)).append('\n');
            }
            return out.toString();
        }
    }

    /** The braces of the template {@code name} defines. */
    @Nonnull
    private static String template(@Nonnull String ui, @Nonnull String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*Group\\s*\\{").matcher(ui);
        assertTrue(m.find(), "the document defines " + name);
        int depth = 0;
        for (int i = m.end() - 1; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return ui.substring(m.end() - 1, i + 1);
            }
        }
        throw new AssertionError(name + " never closes");
    }
}
