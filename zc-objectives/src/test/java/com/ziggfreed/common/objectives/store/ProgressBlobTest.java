package com.ziggfreed.common.objectives.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The packing every persisted map goes through. Fixtures are authored here, so nothing in this file
 * depends on anybody's content.
 */
class ProgressBlobTest {

    @Test
    void stringMapsSurviveARoundTrip() {
        Map<String, String> authored = new LinkedHashMap<>();
        authored.put("q_first", "ACTIVE");
        authored.put("q_second", "COMPLETED");

        String packed = ProgressBlob.serializeStrings(authored);

        assertEquals("q_first=ACTIVE|q_second=COMPLETED", packed,
                "the wire form is a contract: it is what a saved world holds");
        assertEquals(authored, ProgressBlob.deserializeStrings(packed));
    }

    @Test
    void longMapsSurviveARoundTrip() {
        Map<String, Long> authored = new LinkedHashMap<>();
        authored.put("a#0", Long.valueOf(7L));
        authored.put("a#1", Long.valueOf(-3L));

        assertEquals(authored, ProgressBlob.deserializeLongs(ProgressBlob.serializeLongs(authored)));
    }

    @Test
    void anOpaqueValueMayContainTheSeparatorsBecauseItTravelsEncoded() {
        Map<String, String> authored = new LinkedHashMap<>();
        authored.put("q_first", "objective=logs|3/5,objective=ore|1/2");

        String packed = ProgressBlob.serializeBase64Values(authored);

        assertEquals(1, packed.chars().filter(c -> c == '=').count(),
                "exactly one pair separator survives: the value's own is inside the encoding");
        assertEquals(authored, ProgressBlob.deserializeBase64Values(packed));
    }

    @Test
    void anEmptyMapPacksToAnEmptyStringAndBack() {
        assertEquals("", ProgressBlob.serializeStrings(Map.of()));
        assertTrue(ProgressBlob.deserializeStrings("").isEmpty());
        assertTrue(ProgressBlob.deserializeStrings(null).isEmpty());
    }

    @Test
    void aMalformedBlobDegradesToWhateverItCanRead() {
        Map<String, String> read = ProgressBlob.deserializeStrings("good=yes|nonsense||=headless|trailing=");

        assertEquals(Map.of("good", "yes"), read,
                "a corrupted entry costs that entry, never the player's whole login");
    }

    @Test
    void anUnparseableNumberDropsOnlyItsOwnEntry() {
        Map<String, Long> read = ProgressBlob.deserializeLongs("kept=4|broken=twelve");

        assertEquals(Map.of("kept", Long.valueOf(4L)), read);
    }

    @Test
    void anUndecodableOpaqueValueDropsOnlyItsOwnEntry() {
        String packed = ProgressBlob.serializeBase64Values(Map.of("kept", "payload"));

        Map<String, String> read = ProgressBlob.deserializeBase64Values(packed + "|broken=!!!!");

        assertEquals(Map.of("kept", "payload"), read);
    }
}
