package com.ziggfreed.common.ui.hud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
 * A row wording its own number is bound the whole figure at every magnitude. A row whose moves add
 * up to a loss shows its size on the loss key ({@code -{0, number}}), and a row at nothing shows no
 * number at all.
 */
class HudRowGainTest {

    private static long longParam(Message m) {
        return ((LongParamValue) m.getFormattedMessage().params.get("0")).value;
    }

    private static String rawParam(Message m) {
        return m.getFormattedMessage().messageParams.get("0").rawText;
    }

    @Test
    void belowTheThresholdTheGainIsATypedWholeNumber() {
        Message m = HudPanelHud.gain(9_999, null);

        assertEquals(HudPanelHud.GAIN_KEY, m.getMessageId());
        assertEquals(9_999L, longParam(m), "a long, grouped by the client");
        assertNull(m.getFormattedMessage().messageParams, "no server-written text");
        assertEquals(1L, longParam(HudPanelHud.gain(1, null)));
    }

    @Test
    void fromTheThresholdUpTheGainIsTheSharedCompactFormOnTheCompactKey() {
        for (long value : new long[] {10_000, 999_999, 1_000_000}) {
            Message m = HudPanelHud.gain(value, null);

            assertEquals(HudPanelHud.GAIN_COMPACT_KEY, m.getMessageId(), "at " + value);
            assertEquals(NumberFormatter.compact(value, HudPanelHud.COMPACT_GAIN_FROM), rawParam(m),
                    "the one formatter the family compresses a figure with, at the panel's own threshold: " + value);
            assertNull(m.getFormattedMessage().params, "no typed number rides beside it: " + value);
        }
        assertEquals("10.0k", rawParam(HudPanelHud.gain(10_000, null)));
        assertTrue(rawParam(HudPanelHud.gain(999_999, null)).endsWith("k"), "still thousands");
        assertEquals("1.0M", rawParam(HudPanelHud.gain(1_000_000, null)));
        assertEquals(10_000L, HudPanelHud.COMPACT_GAIN_FROM);
    }

    @Test
    void aRowWordingItsOwnNumberIsBoundTheWholeFigureAtEveryMagnitude() {
        Message m = HudPanelHud.gain(10_000, "yourmod.hud.cycles");

        assertEquals("yourmod.hud.cycles", m.getMessageId());
        assertEquals(10_000L, longParam(m), "its key takes the number, and only that mod knows what it has room for");
        assertNull(m.getFormattedMessage().messageParams);
        assertEquals(HudPanelHud.GAIN_COMPACT_KEY, HudPanelHud.gain(10_000, "  ").getMessageId(), "a blank key names none");
    }

    @Test
    void aFractionBelowTheThresholdBindsAsADoubleAndAWholeOneNeverGrowsADecimal() {
        assertInstanceOf(DoubleParamValue.class, HudPanelHud.gain(12.5, null).getFormattedMessage().params.get("0"));
        assertInstanceOf(LongParamValue.class, HudPanelHud.gain(12.0, null).getFormattedMessage().params.get("0"));
    }

    @Test
    void aNetLossIsItsSizeOnTheLossKeyAtEveryMagnitudeAndNothingShowsAtZero() {
        Message m = HudPanelHud.gain(-25, null);

        assertEquals(HudPanelHud.LOSS_KEY, m.getMessageId());
        assertEquals(25L, longParam(m), "the sign is the lang file's, the size a typed number the client groups");
        assertEquals(HudPanelHud.LOSS_KEY, HudPanelHud.gain(-12_000, null).getMessageId(),
                "a loss has no compact twin");
        assertEquals(12_000L, longParam(HudPanelHud.gain(-12_000, null)));
        assertEquals("yourmod.hud.cycles", HudPanelHud.gain(-3, "yourmod.hud.cycles").getMessageId(),
                "a row wording its own number words a loss too");

        assertTrue(HudPanelHud.showsFigure(1));
        assertTrue(HudPanelHud.showsFigure(-1), "a loss is shown beside the name, not hidden");
        assertFalse(HudPanelHud.showsFigure(0), "a row whose moves cancel out shows no number");
    }

    @Test
    void everyWordingTheNumberUsesShipsInEnglish() throws IOException {
        Map<String, String> english = new HashMap<>();
        for (String raw : Files.readAllLines(Path.of("src", "main", "resources", "Server", "Languages", "en-US",
                "ziggfreedcommon.ui.lang"), StandardCharsets.UTF_8)) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (!line.isEmpty() && !line.startsWith("#") && eq > 0) {
                english.put("ziggfreedcommon.ui." + line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        for (String key : new String[] {HudPanelHud.GAIN_KEY, HudPanelHud.GAIN_COMPACT_KEY, HudPanelHud.LOSS_KEY}) {
            assertTrue(english.containsKey(key), "en-US ziggfreedcommon.ui.lang carries " + key);
        }
        assertTrue(english.get(HudPanelHud.LOSS_KEY).startsWith("-"), "the loss wording carries its own sign");
    }
}
