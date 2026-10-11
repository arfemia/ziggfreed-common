package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * What a Beyond payout owes and how the record keeps it: the high-water rule on earned standing (a fall and a
 * climb back owe nothing twice, a player already past the top starts where they stand, and a later change to
 * Every neither pays back nor stalls), the most one Collect press pays, and the saved record read back through
 * the engine codec.
 */
class ReputationComponentTest {

    /** Exalted's floor and the Every of the shipped Beyonds. */
    private static final int FLOOR = 21_000;
    private static final int EVERY = 5_000;

    private static ReputationComponent.Climb climb(long before, long after, Long highest) {
        return ReputationComponent.climb(before, after, highest, FLOOR, EVERY);
    }

    @Test
    void aRiseOwesOnlyTheStepsPastTheHighestStandingEverReached() {
        assertEquals(new ReputationComponent.Climb(3, 36_000), climb(20_000, 36_000, null),
                "from below the top to 36,000 crosses 26,000, 31,000 and 36,000");
        assertEquals(new ReputationComponent.Climb(0, 36_000), climb(36_000, 26_000, 36_000L),
                "a fall owes nothing and keeps the highest");
        assertEquals(new ReputationComponent.Climb(0, 36_000), climb(26_000, 36_000, 36_000L),
                "climbing back to where they stood owes nothing again");
        assertEquals(new ReputationComponent.Climb(1, 41_000), climb(26_000, 41_000, 36_000L),
                "only the step past the highest is new");
        assertEquals(new ReputationComponent.Climb(1, 56_000), climb(51_000, 56_000, null),
                "with nothing on record a player already past the top starts where they stand: no back pay");
        assertEquals(new ReputationComponent.Climb(0, 51_000), climb(51_000, 46_000, null),
                "and a first fall with nothing on record still remembers where they stood");
        assertEquals(0, ReputationLadder.beyondSteps(500_000, FLOOR, 0), "no Every, no steps");
    }

    @Test
    void aLaterChangeToEveryNeitherPaysBackNorStalls() {
        ReputationComponent halved = new ReputationComponent();
        assertEquals(3, halved.climbed("Test_Hallowed", 20_000, 36_000, FLOOR, EVERY));
        assertEquals(0, halved.climbed("Test_Hallowed", 36_000, 26_000, FLOOR, EVERY / 2), "Every halved, a fall");
        assertEquals(0, halved.climbed("Test_Hallowed", 26_000, 36_000, FLOOR, EVERY / 2),
                "and the climb back to the same standing owes nothing new, though it crosses four new multiples");
        assertEquals(1, halved.climbed("Test_Hallowed", 36_000, 38_500, FLOOR, EVERY / 2),
                "the next multiple above the old standing pays at the new Every");
        assertEquals(4, halved.owed("Test_Hallowed"));

        ReputationComponent doubled = new ReputationComponent();
        assertEquals(3, doubled.climbed("Test_Hallowed", 20_000, 36_000, FLOOR, EVERY));
        assertEquals(1, doubled.climbed("Test_Hallowed", 36_000, 41_000, FLOOR, EVERY * 2),
                "Every doubled: the next step above the old standing (41,000) pays, with no stall");
        assertEquals(4, doubled.owed("Test_Hallowed"));
    }

    @Test
    void theRecordKeepsWhatWaitsPerReputationWhateverTheCasing() {
        ReputationComponent record = new ReputationComponent();
        assertEquals(3, record.climbed("Test_Hallowed", 20_000, 36_000, FLOOR, EVERY));
        assertEquals(0, record.climbed("test_hallowed", 36_000, 26_000, FLOOR, EVERY), "a fall");
        assertEquals(0, record.climbed("TEST_HALLOWED", 26_000, 36_000, FLOOR, EVERY), "and the climb back");
        assertEquals(3, record.owed("test_hallowed"), "the three crossings wait, once each");
        assertEquals(Long.valueOf(36_000), record.highest("Test_Hallowed"), "the highest is earned standing");
        assertEquals(0, record.owed("Test_Festival"), "another reputation owes nothing");
        assertNull(record.highest("Test_Festival"), "and has nothing on record");

        record.paid("Test_Hallowed", 2);
        assertEquals(1, record.owed("Test_Hallowed"), "only what was paid comes off");
        record.paid("Test_Hallowed", 5);
        assertEquals(0, record.owed("Test_Hallowed"), "never below none");
        assertEquals(Long.valueOf(36_000), record.highest("Test_Hallowed"), "collecting never lowers the highest");
    }

    @Test
    void onePressPaysWhatWaitsUpToTwenty() {
        assertEquals(0, ReputationComponent.perPress(0));
        assertEquals(7, ReputationComponent.perPress(7));
        assertEquals(ReputationComponent.MAX_AT_ONCE, ReputationComponent.perPress(45));
        assertEquals(20, ReputationComponent.MAX_AT_ONCE);

        ReputationComponent record = new ReputationComponent();
        record.climbed("Test_Hallowed", 0, FLOOR + 45L * EVERY, FLOOR, EVERY);
        assertEquals(45, record.owed("Test_Hallowed"));
        record.paid("Test_Hallowed", ReputationComponent.perPress(record.owed("Test_Hallowed")));
        assertEquals(25, record.owed("Test_Hallowed"), "the rest keep waiting for the next press");
    }

    @Test
    void theRecordRoundTripsThroughTheEngineCodecAndAbsentMeansNothingOwed() throws IOException {
        ReputationComponent record = new ReputationComponent();
        record.climbed("Test_Hallowed", 20_000, 41_000, FLOOR, EVERY);
        record.paid("Test_Hallowed", 2);
        record.climbed("Test_Festival", 31_000, 36_000, FLOOR, EVERY);
        assertEquals("Test_Festival/highest:36000|Test_Festival/owed:1|Test_Hallowed/highest:41000|Test_Hallowed/owed:2",
                ReputationComponent.serialize(record.beyond.all()), "one sorted string, so a save is stable");

        ExtraInfo info = new ExtraInfo();
        ReputationComponent restarted = ReputationComponent.CODEC.decode(ReputationComponent.CODEC.encode(record, info),
                info);
        assertEquals(2, restarted.owed("test_hallowed"));
        assertEquals(Long.valueOf(41_000), restarted.highest("test_hallowed"));
        assertEquals(1, restarted.owed("Test_Festival"));

        ReputationComponent saved = ReputationComponent.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{\"Beyond\":\"Test_Hallowed/owed:2|garbage|Test_Hallowed/highest:x\"}"),
                new ExtraInfo());
        assertEquals(2, saved.owed("Test_Hallowed"), "the saved key is Beyond, and a damaged pair costs only itself");
        assertNull(saved.highest("Test_Hallowed"));

        ReputationComponent before = ReputationComponent.CODEC.decodeJson(RawJsonReader.fromJsonString("{}"),
                new ExtraInfo());
        assertEquals(0, before.owed("Test_Hallowed"), "a player saved before the record existed is owed nothing");
        assertTrue(before.beyond.isEmpty());

        ReputationComponent copy = record.clone();
        copy.paid("Test_Hallowed", 2);
        assertEquals(2, record.owed("Test_Hallowed"), "a copy is independent of its original");
        assertFalse(ReputationComponent.keeps("Bad|Id"), "an id carrying the pair separator cannot be kept");
    }
}
