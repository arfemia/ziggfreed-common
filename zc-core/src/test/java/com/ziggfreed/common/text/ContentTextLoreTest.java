package com.ziggfreed.common.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/** The {@code Lore} group on the shared {@code Text} block: one plain paragraph per lifecycle state. */
class ContentTextLoreTest {

    private static ContentTextAsset decode(String json) throws IOException {
        return ContentTextAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void theThreeStatesDecodeUnderTheirOwnKeysAndReadBackByStateWord() throws Exception {
        ContentTextAsset text = decode("""
                { "TitleKey": "yourmod.thing.title",
                  "Lore": { "Incomplete": "Not yet.", "Active": "Now.", "Complete": "Done." } }
                """);

        ContentTextAsset.Lore lore = text.getLore();
        assertNotNull(lore);
        assertEquals("Not yet.", lore.getIncomplete());
        assertEquals("Now.", lore.getActive());
        assertEquals("Done.", lore.getComplete());
        assertEquals(Map.of("incomplete", "Not yet.", "active", "Now.", "complete", "Done."), text.loreMap(),
                "keyed by the state words every surface asks the runtime text by");
        assertEquals("yourmod.thing.title", text.getTitleKey(), "the sibling leaves are untouched");
    }

    @Test
    void anUnauthoredGroupReadsAsNothingAndABlankParagraphIsDropped() throws Exception {
        assertNull(decode("{ \"TitleKey\": \"yourmod.thing.title\" }").getLore());
        assertTrue(decode("{ \"TitleKey\": \"yourmod.thing.title\" }").loreMap().isEmpty());

        ContentTextAsset partial = decode("{ \"Lore\": { \"Active\": \"  \", \"Complete\": \"Done.\" } }");
        assertEquals(Map.of("complete", "Done."), partial.loreMap());
    }

    @Test
    void theJavaFactoryMapsTheSameWayTheCodecDoes() {
        ContentTextAsset.Lore lore = ContentTextAsset.Lore.of("Not yet.", null, "Done.");

        assertEquals(Map.of(ContentTextAsset.Lore.STATE_INCOMPLETE, "Not yet.",
                ContentTextAsset.Lore.STATE_COMPLETE, "Done."), lore.toMap());
    }
}
