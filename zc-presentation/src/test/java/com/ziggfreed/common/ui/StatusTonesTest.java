package com.ziggfreed.common.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.kit.Tone;

/**
 * The status vocabulary is the kit's: each name answers to the tone of its meaning and reads that tone's text
 * colour (so the Java table follows the kit's tokens, held to {@code Common/ZigTokens.ui}), the six names its callers
 * use are kept, and Collect is new.
 */
class StatusTonesTest {

    @Test
    void eachStatusReadsItsTonesTextColour() {
        for (StatusTones status : StatusTones.values()) {
            assertEquals(status.tone().textHex(), status.hex(), status.name());
            assertTrue(UiRetint.isSixDigitHex(status.hex()), status + " pushes a #rrggbb");
        }
    }

    @Test
    void eachStatusAnswersToTheToneOfItsMeaning() {
        assertEquals(Tone.DONE, StatusTones.READY.tone());
        assertEquals(Tone.COLLECT, StatusTones.COLLECT.tone());
        assertEquals(Tone.AVAILABLE, StatusTones.AVAILABLE.tone());
        assertEquals(Tone.ACTIVE, StatusTones.IN_PROGRESS.tone());
        assertEquals(Tone.BLOCKED, StatusTones.SOFT_BLOCK.tone());
        assertEquals(Tone.WAITING, StatusTones.LIMITED.tone());
        assertEquals(Tone.DANGER, StatusTones.LOCKED.tone());
    }

    @Test
    void theCallersNamesAreKeptAndCollectIsNew() {
        Set<String> names = new HashSet<>();
        for (StatusTones status : StatusTones.values()) {
            names.add(status.name());
        }
        assertEquals(Set.copyOf(List.of("READY", "COLLECT", "AVAILABLE", "IN_PROGRESS", "SOFT_BLOCK", "LIMITED",
                "LOCKED")), names);
    }
}
