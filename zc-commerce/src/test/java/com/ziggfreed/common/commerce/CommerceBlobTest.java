package com.ziggfreed.common.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire form every saved world holds. It is a CONTRACT rather than an implementation detail, so
 * these assertions are about the exact text as much as about the round trip.
 */
class CommerceBlobTest {

    @Test
    @DisplayName("a string map packs as key=value|key=value and comes back the same")
    void stringsRoundTrip() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("Featured", "Copper_Cache");
        map.put("Xp", "Xp_Packet");

        String packed = CommerceBlob.serializeStrings(map);
        assertEquals("Featured=Copper_Cache|Xp=Xp_Packet", packed);
        assertEquals(map, CommerceBlob.deserializeStrings(packed));
    }

    @Test
    @DisplayName("a long map packs the same way, and an unreadable number costs that entry alone")
    void longsRoundTripAndDegrade() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("Bounty_Token", Long.valueOf(300L));
        map.put("Life_Essence", Long.valueOf(7L));

        assertEquals("Bounty_Token=300|Life_Essence=7", CommerceBlob.serializeLongs(map));
        assertEquals(map, CommerceBlob.deserializeLongs("Bounty_Token=300|Life_Essence=7"));

        Map<String, Long> partial = CommerceBlob.deserializeLongs("Good=1|Bad=x|Also_Good=2");
        assertEquals(2, partial.size(), "a corrupted number must never cost the other entries");
        assertEquals(Long.valueOf(1L), partial.get("Good"));
        assertEquals(Long.valueOf(2L), partial.get("Also_Good"));
    }

    @Test
    @DisplayName("a set survives ids carrying the format's own reserved characters")
    void setsSurviveReservedCharacters() {
        Set<String> ids = new LinkedHashSet<>();
        ids.add("Plain_Id");
        ids.add("weird|id=with=both,and,commas");

        String packed = CommerceBlob.serializeSet(ids);
        assertTrue(packed.indexOf('|') < 0, "the packed form must not carry the entry separator");
        assertEquals(ids, CommerceBlob.deserializeSet(packed),
                "each id is encoded on its own, so an id carrying the SET separator cannot split");
    }

    @Test
    @DisplayName("a packed set survives being a VALUE inside the pair format")
    void aPackedSetNestsInsideAPair() {
        Set<String> ids = new LinkedHashSet<>();
        ids.add("First");
        ids.add("Second");

        Map<String, String> outer = new LinkedHashMap<>();
        outer.put("Daily#0", CommerceBlob.serializeSet(ids));

        Map<String, String> read = CommerceBlob.deserializeStrings(CommerceBlob.serializeStrings(outer));
        assertEquals(ids, CommerceBlob.deserializeSet(read.get("Daily#0")),
                "base64 padding is an '=', and a pair must split on the FIRST one");
    }

    @Test
    @DisplayName("nothing in, nothing out - and nothing thrown")
    void everyMethodIsTotal() {
        assertEquals("", CommerceBlob.serializeStrings(null));
        assertEquals("", CommerceBlob.serializeLongs(null));
        assertEquals("", CommerceBlob.serializeSet(null));
        assertTrue(CommerceBlob.deserializeStrings(null).isEmpty());
        assertTrue(CommerceBlob.deserializeLongs("   ").isEmpty());
        assertTrue(CommerceBlob.deserializeSet("not base64 at all!").isEmpty());
        assertTrue(CommerceBlob.deserializeStrings("no separator here").isEmpty());
    }
}
