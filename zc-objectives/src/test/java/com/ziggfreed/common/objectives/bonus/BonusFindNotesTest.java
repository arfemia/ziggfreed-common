package com.ziggfreed.common.objectives.bonus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.trigger.BonusMoment;

/** A burst of finds speaks its own count, and a count it held unspoken never reaches a later burst's note. */
class BonusFindNotesTest {

    private static final UUID PLAYER = new UUID(0L, 42L);

    @AfterEach
    void forget() {
        BonusFindNotes.clearForTests();
    }

    @Test
    void aBurstSpeaksItsRunningTotalOnceTheWindowHasPassed() {
        BonusMoment moment = BonusMoment.BREAK_BLOCK;
        long window = BonusFindNotes.BATCH_MS;
        assertEquals(1L, BonusFindNotes.gather(PLAYER, moment, 1, 0L));
        assertEquals(0L, BonusFindNotes.gather(PLAYER, moment, 1, window / 4));
        assertEquals(0L, BonusFindNotes.gather(PLAYER, moment, 1, window / 2));
        assertEquals(3L, BonusFindNotes.gather(PLAYER, moment, 1, window));
    }

    @Test
    void aCountHeldUnspokenEndsWithItsBurst() {
        BonusMoment moment = BonusMoment.BREAK_BLOCK;
        // One break pays two groups: the first speaks, the second is held.
        assertEquals(1L, BonusFindNotes.gather(PLAYER, moment, 1, 0L));
        assertEquals(0L, BonusFindNotes.gather(PLAYER, moment, 1, 0L));
        // Long after, a single find speaks for itself alone.
        assertEquals(1L, BonusFindNotes.gather(PLAYER, moment, 1, BonusFindNotes.BATCH_MS * 600));
    }
}
