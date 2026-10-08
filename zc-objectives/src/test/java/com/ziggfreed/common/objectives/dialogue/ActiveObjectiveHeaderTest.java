package com.ziggfreed.common.objectives.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.hypixel.hytale.server.core.Message;

/**
 * The note under a character's name names the quest and its step ("Pumpkin Patch: Bring 3 Hallowed
 * Pumpkins (0/3)"): the quest's name and the step nest as translated messages, and the tally's two
 * numbers are typed params the lang value places, never digits and brackets spelt on the server.
 */
class ActiveObjectiveHeaderTest {

    private static final String LANG = "Server/Languages/en-US/ziggfreedcommon.dialogue.lang";

    @Test
    void aCountedStepCarriesTheQuestTheStepAndTwoTypedNumbers() {
        FormattedMessage fm = ActiveObjectiveHeader.line(Message.raw("Pumpkin Patch"),
                Message.raw("Bring 3 Hallowed Pumpkins"), 0, 3).getFormattedMessage();

        assertEquals(ActiveObjectiveHeader.COUNTED_KEY, fm.messageId);
        assertEquals("Pumpkin Patch", fm.messageParams.get("0").rawText);
        assertEquals("Bring 3 Hallowed Pumpkins", fm.messageParams.get("1").rawText);
        assertEquals(0, ((IntParamValue) fm.params.get("2")).value);
        assertEquals(3, ((IntParamValue) fm.params.get("3")).value);
    }

    @Test
    void aStepWithNothingToCountIsTheQuestAndTheStepAlone() {
        FormattedMessage fm = ActiveObjectiveHeader.line(Message.raw("Meet Old Jack"),
                Message.raw("Talk to Old Jack"), 0, 1).getFormattedMessage();

        assertEquals(ActiveObjectiveHeader.LINE_KEY, fm.messageId);
        assertEquals("Meet Old Jack", fm.messageParams.get("0").rawText);
        assertEquals("Talk to Old Jack", fm.messageParams.get("1").rawText);
        assertTrue(fm.params == null || fm.params.isEmpty(), "no tally to place");
    }

    @Test
    void bothKeysShipInEnglishWithTheirNumbersTyped() throws IOException {
        String lang;
        try (InputStream in = ActiveObjectiveHeaderTest.class.getClassLoader().getResourceAsStream(LANG)) {
            assertNotNull(in, LANG + " ships");
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String prefix = "ziggfreedcommon.dialogue.";
        String line = valueOf(lang, ActiveObjectiveHeader.LINE_KEY.substring(prefix.length()));
        String counted = valueOf(lang, ActiveObjectiveHeader.COUNTED_KEY.substring(prefix.length()));
        assertTrue(line.contains("{0}") && line.contains("{1}"), line);
        assertTrue(counted.contains("{2, number}") && counted.contains("{3, number}"), counted);
    }

    private static String valueOf(String lang, String key) {
        for (String raw : lang.split("\\R")) {
            String row = raw.trim();
            if (row.startsWith(key + " =")) {
                return row.substring(row.indexOf('=') + 1).trim();
            }
        }
        throw new AssertionError(key + " ships in en-US");
    }
}
