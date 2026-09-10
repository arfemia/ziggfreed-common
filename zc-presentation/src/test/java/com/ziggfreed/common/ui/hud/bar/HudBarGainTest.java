package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.DoubleParamValue;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.util.NumberFormatter;

/**
 * The number beside a bar's name. Below ten thousand it is a typed numeric param on the panel's
 * {@code +{0, number}} key, so each player's client groups the digits in its own locale; from ten
 * thousand up it is the family's one compact form ("12.3k", {@link NumberFormatter#compact}) on
 * the key's compact twin, because the wide panel's gain column has no room for the grouped figure.
 * A row wording its own number is bound the whole figure at every magnitude.
 */
class HudBarGainTest {

    private static long longParam(Message m) {
        return ((LongParamValue) m.getFormattedMessage().params.get("0")).value;
    }

    private static String rawParam(Message m) {
        return m.getFormattedMessage().messageParams.get("0").rawText;
    }

    @Test
    void belowTheThresholdTheGainIsATypedWholeNumber() {
        Message m = HudBarHud.gain(9_999, null);

        assertEquals(HudBarHud.GAIN_KEY, m.getMessageId());
        assertEquals(9_999L, longParam(m), "a long, grouped by the client");
        assertNull(m.getFormattedMessage().messageParams, "no server-written text");
        assertEquals(1L, longParam(HudBarHud.gain(1, null)));
    }

    @Test
    void fromTheThresholdUpTheGainIsTheSharedCompactFormOnTheCompactKey() {
        for (long value : new long[] {10_000, 999_999, 1_000_000}) {
            Message m = HudBarHud.gain(value, null);

            assertEquals(HudBarHud.GAIN_COMPACT_KEY, m.getMessageId(), "at " + value);
            assertEquals(NumberFormatter.compact(value, HudBarHud.COMPACT_GAIN_FROM), rawParam(m),
                    "the one formatter the family compresses a figure with, at the panel's own threshold: " + value);
            assertNull(m.getFormattedMessage().params, "no typed number rides beside it: " + value);
        }
        assertEquals("10.0k", rawParam(HudBarHud.gain(10_000, null)));
        assertTrue(rawParam(HudBarHud.gain(999_999, null)).endsWith("k"), "still thousands");
        assertEquals("1.0M", rawParam(HudBarHud.gain(1_000_000, null)));
        assertEquals(10_000L, HudBarHud.COMPACT_GAIN_FROM);
    }

    @Test
    void aRowWordingItsOwnNumberIsBoundTheWholeFigureAtEveryMagnitude() {
        Message m = HudBarHud.gain(10_000, "yourmod.hud.cycles");

        assertEquals("yourmod.hud.cycles", m.getMessageId());
        assertEquals(10_000L, longParam(m), "its key takes the number, and only that mod knows what it has room for");
        assertNull(m.getFormattedMessage().messageParams);
        assertEquals(HudBarHud.GAIN_COMPACT_KEY, HudBarHud.gain(10_000, "  ").getMessageId(), "a blank key names none");
    }

    @Test
    void aFractionBelowTheThresholdBindsAsADoubleAndAWholeOneNeverGrowsADecimal() {
        assertInstanceOf(DoubleParamValue.class, HudBarHud.gain(12.5, null).getFormattedMessage().params.get("0"));
        assertInstanceOf(LongParamValue.class, HudBarHud.gain(12.0, null).getFormattedMessage().params.get("0"));
    }
}
