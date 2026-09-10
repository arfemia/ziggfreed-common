package com.ziggfreed.common.ui.hud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.card.HudCardLook;

/**
 * The dressing follows the card: at full opacity nothing is pushed and the documents' own
 * constants stand; at a fraction each overlay keeps its own hue and has its shipped alpha
 * multiplied by that fraction; and the constants the Java mirrors are the ones both bar documents
 * actually declare, so the two cannot drift apart unnoticed.
 */
class HudBarDressingTest {

    private static final List<Path> DOCUMENTS = List.of(
            Path.of("src/main/resources/Common/UI/Custom/Hud/ZigHudBars.ui"),
            Path.of("src/main/resources/Common/UI/Custom/Hud/ZigHudBarGrid.ui"));

    @Test
    void fullOpacityLeavesTheDocumentsConstantsAndPushesNothing() {
        assertNull(HudBarDressing.at(1.0));
        assertNull(HudBarDressing.at(1.5), "past full is full");
    }

    @Test
    void aFractionDimsEachOverlayAtItsOwnHueByTheCardsOpacity() {
        double opacity = 0.5;
        HudBarDressing dressing = HudBarDressing.at(opacity);
        assertNotNull(dressing);

        assertOverlay(dressing.well(), HudBarDressing.WELL_RGB, HudBarDressing.WELL_ALPHA, opacity);
        assertOverlay(dressing.gloss(), HudBarDressing.GLOSS_RGB, HudBarDressing.GLOSS_ALPHA, opacity);
        assertOverlay(dressing.shade(), HudBarDressing.SHADE_RGB, HudBarDressing.SHADE_ALPHA, opacity);

        HudBarDressing gone = HudBarDressing.at(0.0);
        assertNotNull(gone);
        assertTrue(gone.well().endsWith("00"), "a fully transparent card leaves no well");
    }

    private static void assertOverlay(String pushed, String shippedRgb, double shippedAlpha, double opacity) {
        assertEquals(shippedRgb, pushed.substring(0, 7), "the hue is the document's own");
        int alphaByte = Integer.parseInt(pushed.substring(7), 16);
        assertEquals((int) Math.round(shippedAlpha * opacity * 255.0), alphaByte,
                "the shipped alpha multiplied by the card's opacity");
        assertEquals(HudCardLook.dimmed(shippedRgb, shippedAlpha, opacity), pushed, "the one derivation rule");
    }

    @Test
    void theJavaMirrorsMatchWhatBothDocumentsDeclare() throws IOException {
        for (Path document : DOCUMENTS) {
            String ui = Files.readString(document, StandardCharsets.UTF_8);
            assertTrue(ui.contains(declaration("@TrackWell", HudBarDressing.WELL_RGB, HudBarDressing.WELL_ALPHA)),
                    document + " declares the well the Java mirrors");
            assertTrue(ui.contains(declaration("@Gloss", HudBarDressing.GLOSS_RGB, HudBarDressing.GLOSS_ALPHA)),
                    document + " declares the gloss the Java mirrors");
            assertTrue(ui.contains(declaration("@BaseShade", HudBarDressing.SHADE_RGB, HudBarDressing.SHADE_ALPHA)),
                    document + " declares the shade the Java mirrors");
        }
    }

    /** The document's own spelling of a colour-and-alpha constant: {@code @Name = #rrggbb(alpha);}. */
    private static String declaration(String name, String rgb, double alpha) {
        return String.format(Locale.ROOT, "%s = %s(%s);", name, rgb, alpha);
    }
}
