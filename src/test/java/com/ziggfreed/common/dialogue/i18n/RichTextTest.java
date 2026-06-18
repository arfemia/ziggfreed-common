package com.ziggfreed.common.dialogue.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.hypixel.hytale.server.core.Message;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RichText} inline-markup -> {@link Message}-tree parser.
 * Pure (no engine/I18n); asserts the resulting span tree's text + color + bold/italic.
 */
class RichTextTest {

    /** A parsed result is either a single segment or an empty root with child segments. */
    private static List<Message> segments(Message m) {
        return m.getChildren().isEmpty() ? List.of(m) : m.getChildren();
    }

    private static boolean isBold(Message m) {
        return Boolean.TRUE.equals(m.getFormattedMessage().bold);
    }

    private static boolean isItalic(Message m) {
        return Boolean.TRUE.equals(m.getFormattedMessage().italic);
    }

    @Test
    void plainTextIsOneUncolouredSegment() {
        Message m = RichText.parse("Just plain prose.");
        List<Message> segs = segments(m);
        assertEquals(1, segs.size());
        assertEquals("Just plain prose.", segs.get(0).getRawText());
        assertNull(segs.get(0).getColor());
    }

    @Test
    void colorTagColoursOnlyItsSpan() {
        Message m = RichText.parse("a<color is=\"#ff0000\">b</color>c");
        List<Message> segs = segments(m);
        assertEquals(3, segs.size());
        assertEquals("a", segs.get(0).getRawText());
        assertNull(segs.get(0).getColor());
        assertEquals("b", segs.get(1).getRawText());
        assertEquals("#ff0000", segs.get(1).getColor());
        assertEquals("c", segs.get(2).getRawText());
        assertNull(segs.get(2).getColor());
    }

    @Test
    void boldAndItalicTags() {
        Message bold = RichText.parse("<b>loud</b>");
        assertTrue(isBold(segments(bold).get(0)));
        Message ital = RichText.parse("<i>soft</i>");
        assertTrue(isItalic(segments(ital).get(0)));
    }

    @Test
    void nestedTagsCombineStyles() {
        Message m = RichText.parse("<i><color is=\"#7affa0\">green italic</color></i>");
        Message seg = segments(m).get(0);
        assertEquals("green italic", seg.getRawText());
        assertEquals("#7affa0", seg.getColor());
        assertTrue(isItalic(seg));
    }

    @Test
    void closingTagPopsBackToParentStyle() {
        // After </color>, the trailing text is back to italic-only (no colour).
        Message m = RichText.parse("<i>warm <color is=\"#ff0000\">danger</color> warm</i>");
        List<Message> segs = segments(m);
        assertEquals(3, segs.size());
        assertNull(segs.get(0).getColor());
        assertTrue(isItalic(segs.get(0)));
        assertEquals("#ff0000", segs.get(1).getColor());
        assertTrue(isItalic(segs.get(1)));
        assertNull(segs.get(2).getColor());
        assertTrue(isItalic(segs.get(2)));
    }

    @Test
    void literalBackslashNBecomesNewline() {
        Message m = RichText.parse("line one\\nline two");
        assertEquals("line one\nline two", segments(m).get(0).getRawText());
    }

    @Test
    void unknownTagIsDroppedButTextKept() {
        Message m = RichText.parse("keep <span>this</span> text");
        // No supported style applied; the text survives across the unknown tag pair.
        StringBuilder all = new StringBuilder();
        for (Message s : segments(m)) {
            all.append(s.getRawText());
            assertNull(s.getColor());
        }
        assertEquals("keep this text", all.toString());
    }

    @Test
    void hasMarkupDetectsTagsOnly() {
        assertTrue(RichText.hasMarkup("<color is=\"#fff\">x</color>"));
        assertTrue(RichText.hasMarkup("<b>x</b>"));
        assertTrue(RichText.hasMarkup("<i>x</i>"));
        assertFalse(RichText.hasMarkup("plain text"));
        assertFalse(RichText.hasMarkup("a < b math"));
        assertFalse(RichText.hasMarkup(null));
    }
}
