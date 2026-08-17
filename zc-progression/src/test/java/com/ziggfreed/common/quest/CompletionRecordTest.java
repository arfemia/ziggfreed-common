package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.QuestProgressStore.CompletionRecord;

/**
 * The record's own invariants, driven straight through the constructor.
 *
 * <p>The clamp is the load-bearing one. The collected tally arrives from a save file, so it is the
 * one number here nothing upstream vouches for, and clamping it in the constructor is the ONLY thing
 * standing between a bad value and every reading written against it. It is also what lets a
 * collection be recorded without first checking the finish count beside it, which is what makes
 * "collect a run finished before this tally existed" add nothing instead of counting twice.
 *
 * <p>Every number here is a fixture the test authors; none of it is balance data.
 */
class CompletionRecordTest {

    @Test
    void collectedIsClampedToFinished() {
        assertEquals(3, new CompletionRecord(1_000L, 1, 3, 9).claimedCount(),
                "more collected than finished is not a history a player can have");
        assertEquals(0, new CompletionRecord(0L, 0, 0, 5).claimedCount(),
                "nothing finished means nothing collected, whatever a value claims");
        assertEquals(2, new CompletionRecord(1_000L, 1, 3, 2).claimedCount(),
                "a collected count that fits is left exactly as it is");
    }

    @Test
    void everyTallyHasAFloorOfZero() {
        CompletionRecord negative = new CompletionRecord(-5L, -1, -2, -3);

        assertEquals(0L, negative.lastCompletionMs());
        assertEquals(0, negative.periodCount());
        assertEquals(0, negative.totalCount());
        assertEquals(0, negative.claimedCount());
        assertTrue(negative.isEmpty(), "nothing survived, so there is nothing recorded");
    }

    @Test
    void theNamedFactoryReadsEveryFinishAsCollected() {
        CompletionRecord noCollectedTally = CompletionRecord.withoutCollectedTally(1_000L, 1, 4);

        assertEquals(4, noCollectedTally.totalCount());
        assertEquals(4, noCollectedTally.claimedCount(),
                "a value that cannot say which of its finishes were collected says all of them");
    }

    @Test
    void emptinessIsAboutTheFinishes() {
        assertTrue(CompletionRecord.NONE.isEmpty());
        assertFalse(new CompletionRecord(0L, 0, 1, 0).isEmpty(),
                "one finish with the reward still owing is a record, not an absence");
    }
}
