package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * What a view tab sends: one template per tab by index, its name on {@code #Label.TextSpans}, its plain picture, and
 * its click; the chosen tab swaps to the "on" button and label styles by reference and shows its gold bar, while a
 * tab at rest keeps its authored look. Tagged {@code engine-items}: a {@link UICommandBuilder}'s static init reaches
 * the engine's item codec.
 */
@Tag("engine-items")
class ViewTabPainterTest {

    private static final String TEXTURE = "UI/Custom/Pages/Memories/MissingIcon.png";

    @Test
    void aTabPaintsItsNameItsPictureItsLookAndItsClick() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        cmd.clear("#Views");
        String chosen = ViewTabPainter.append(cmd, events, "#Views", Message.raw("Overview"), Picture.texture(TEXTURE),
                true, EventData.of("View", "overview"));
        String rest = ViewTabPainter.append(cmd, events, "#Views", Message.raw("Browse"), Picture.texture(TEXTURE),
                false, EventData.of("View", "browse"));
        Painted p = Painted.of(cmd, events);

        assertEquals("#Views[0] #Tab", chosen);
        assertEquals("#Views[1] #Tab", rest);
        assertEquals(List.of("#Views <- " + ViewTabPainter.TEMPLATE, "#Views <- " + ViewTabPainter.TEMPLATE),
                p.appends());
        assertTrue(p.set("#Views[0] #Tab #Label.TextSpans").contains("Overview"));
        assertTrue(p.set("#Views[1] #Tab #Label.TextSpans").contains("Browse"));
        assertTrue(p.shown("#Views[0] #Tab #Pic #IcoTex.Visible"), "the tab's picture is drawn");
        assertTrue(p.set("#Views[1] #Tab #Pic #IcoTex.AssetPath").contains(TEXTURE));

        assertTrue(p.references("#Views[0] #Tab.Style", ZigStyles.DOCUMENT, "ZigViewTabOnStyle"));
        assertTrue(p.references("#Views[0] #Tab #Label.Style", ZigStyles.DOCUMENT, "ZigViewTabLabelOnStyle"));
        assertTrue(p.shown("#Views[0] #Bar.Visible"), "the chosen tab shows its gold bar");
        assertFalse(p.has("#Views[1] #Tab.Style"), "a tab at rest keeps its authored look");
        assertFalse(p.has("#Views[1] #Tab #Label.Style"));
        assertFalse(p.has("#Views[1] #Bar.Visible"), "the bar ships hidden");

        assertNotNull(p.binding("#Views[0] #Tab"));
        assertTrue(p.binding("#Views[1] #Tab").contains("browse"));
        assertEquals(2, p.bindingCount(), "one click per tab");
    }

    @Test
    void aTabWithNoClickIsNotBound() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        ViewTabPainter.append(cmd, events, "#Views", Message.raw("Overview"), Picture.NONE, true, null);
        Painted p = Painted.of(cmd, events);
        assertNull(p.binding("#Views[0] #Tab"));
        assertFalse(p.shown("#Views[0] #Tab #Pic #IcoTex.Visible"), "no picture: the slot stays empty");
    }

    @Test
    void theStripsNumbersAreTheTemplates() {
        assertEquals(ViewTabPainter.WIDTH + ViewTabPainter.GAP, ViewTabPainter.STEP);
        assertTrue(ViewTabPainter.HEIGHT - ViewTabPainter.BAR >= 32, "a tab is at least a control's height");
    }
}
