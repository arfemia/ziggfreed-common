package com.ziggfreed.common.loot.diminish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertFalse(read.isRolling());
        assertNull(read.key());
    }

    @Test
    void aRollingSpanAndAKeyReadAndWriteBack() throws IOException {
        DiminishSpec read = decode("{ \"Steps\": [1, 0.5], \"Window\": \" 10M \", \"Key\": \" Cell16 \" }");
        assertTrue(read.isRolling());
        assertEquals(600_000L, read.rollingMs());
        assertEquals("10m", read.windowWord());
        assertEquals("Cell16", read.key(), "the key reads trimmed");

        ExtraInfo info = new ExtraInfo();
        DiminishSpec again = DiminishSpec.CODEC.decode(DiminishSpec.CODEC.encode(read, info), info);
        assertEquals(read, again, "a rolling spec with a key reads back the same");
        assertEquals(DiminishSpec.rolling(600_000L, 1.0, 0.5).withKey("cell16"), again,
                "the key compares without regard to case");
    }

    @Test
    void aSpanWordReadsInItsLargestWholeUnit() {
        assertEquals(90_000L, DiminishSpec.spanMs("90s"));
        assertEquals(7_200_000L, DiminishSpec.spanMs("2h"));
        assertEquals(86_400_000L, DiminishSpec.spanMs("1d"));
        assertEquals(0L, DiminishSpec.spanMs("0m"), "a span of nothing is no span");
        assertEquals(0L, DiminishSpec.spanMs("10x"));
        assertEquals(0L, DiminishSpec.spanMs("m"));
        assertEquals("90s", DiminishSpec.spanWord(90_000L));
        assertEquals("2h", DiminishSpec.spanWord(7_200_000L));
        assertEquals("1d", DiminishSpec.spanWord(86_400_000L));
    }

    @Test
    void aWordThatIsNeitherAWindowNorASpanFailsTheRead() {
        assertThrows(Exception.class, () -> decode("{ \"Window\": \"Monthly\" }"));
    }
}
