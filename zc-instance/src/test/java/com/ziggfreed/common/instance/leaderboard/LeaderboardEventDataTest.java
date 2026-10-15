package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The records page's event codec hears a split's state: the segment's {@code Split}, and the picker's value under
 * both {@code Pick} and the {@code @Pick} key the client echoes it back under (a page declaring only one spelling
 * reads a dropdown as snapping back to its first entry), and a variant segment's {@code Variant}.
 */
class LeaderboardEventDataTest {

    @Nonnull
    private static LeaderboardEventData decode(@Nonnull String json) throws IOException {
        return LeaderboardEventData.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void aSegmentCarriesItsChoice() throws IOException {
        LeaderboardEventData data = decode("{\"Action\":\"split\",\"Group\":\"hunting\",\"Split\":\"BEASTS\","
                + "\"Pick\":\"\"}");
        assertEquals("split", data.action);
        assertEquals("BEASTS", data.split);
        assertEquals("", data.pick);
    }

    @Test
    void thePickersEchoIsThePick() throws IOException {
        LeaderboardEventData data = decode("{\"Action\":\"pick\",\"Split\":\"BEASTS\",\"@Pick\":\"WOLF\"}");
        assertEquals("WOLF", data.pick);
        assertEquals("BEASTS", data.split);
    }

    @Test
    void theBareSpellingIsThePickToo() throws IOException {
        assertEquals("WOLF", decode("{\"Pick\":\"WOLF\"}").pick);
    }

    @Test
    void aVariantSegmentCarriesItsVariantBesideThePick() throws IOException {
        LeaderboardEventData data = decode("{\"Action\":\"variant\",\"Split\":\"Seasonal\",\"Pick\":\"Horseman\","
                + "\"Variant\":\"2\"}");
        assertEquals("2", data.variant);
        assertEquals("Horseman", data.pick);
        assertEquals("Seasonal", data.split);
    }
}
