package com.ziggfreed.common.loot.diminish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A {@code Diminish} group reads its two leaves as written (the window's word in any case), writes them back
 * the same, and reads as every clear in full on a daily count when nothing is written.
 */
class DiminishSpecCodecTest {

    private static DiminishSpec decode(String json) throws IOException {
        return DiminishSpec.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void theGroupRoundTripsThroughTheCodec() throws IOException {
        DiminishSpec read = decode("{ \"Steps\": [1, 0.5, 0], \"Window\": \"weekly\" }");
        assertEquals(DiminishSpec.Window.WEEKLY, read.window(), "the window's word reads in any case");
        assertArrayEquals(new double[] {1.0, 0.5, 0.0}, read.steps());

        ExtraInfo info = new ExtraInfo();
        DiminishSpec again = DiminishSpec.CODEC.decode(DiminishSpec.CODEC.encode(read, info), info);
        assertEquals(read, again, "what is written reads back the same");
        assertEquals(DiminishSpec.of(DiminishSpec.Window.WEEKLY, 1.0, 0.5, 0.0), again);
    }

    @Test
    void nothingWrittenPaysEveryClearInFullOnADailyCount() throws IOException {
        DiminishSpec read = decode("{}");
        assertEquals(DiminishSpec.Window.DAILY, read.window());
        assertArrayEquals(new double[] {1.0}, read.steps());
    }
}
