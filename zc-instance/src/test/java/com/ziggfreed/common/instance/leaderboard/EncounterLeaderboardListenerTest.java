package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;

/**
 * What a defeat writes: the bucket the binding row's group composes, one row per participant with
 * their share as the score and the fight's length as the time, and nothing at all when the row
 * names no bucket.
 */
class EncounterLeaderboardListenerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Nonnull
    private static EncounterBindingAsset.Leaderboard group(@Nonnull String json) throws IOException {
        EncounterBindingAsset row = EncounterBindingAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString("{ \"Leaderboard\": " + json + " }"), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(EncounterBindingAsset.class, "Warden", null)));
        assertNotNull(row.getLeaderboard(), "the fixture decodes a group");
        return row.getLeaderboard();
    }

    @Nonnull
    private static EncounterDefeatedEvent defeat(@Nonnull String difficulty, double elapsed,
            ParticipantShare... shares) {
        List<ParticipantShare> list = List.of(shares);
        return new EncounterDefeatedEvent(UUID.randomUUID(), "Kweebec_Warden", null, null, "Warden", list,
                list.stream().map(ParticipantShare::playerId).toList(), Map.of(), Map.of(), elapsed, 0,
                difficulty, ALICE);
    }

    @Nonnull
    private static ParticipantShare share(@Nonnull UUID who, @Nonnull String name, double share,
            double dealt, double taken) {
        return new ParticipantShare(who, name, share, share >= 0.05, dealt, taken, 120.0, false);
    }

    // ==================== the bucket ====================

    @Test
    void theBucketIsTheRowsOwnUntilAKnobSplitsIt() throws IOException {
        assertEquals("encounters", EncounterLeaderboardListener.bucketFor(group("{ \"Bucket\": \"encounters\" }"), 4, "hard"));
        assertEquals("encounters:4",
                EncounterLeaderboardListener.bucketFor(group("{ \"Bucket\": \"encounters\", \"ByPartySize\": true }"), 4, "hard"));
        assertEquals("encounters:hard",
                EncounterLeaderboardListener.bucketFor(group("{ \"Bucket\": \"encounters\", \"ByDifficulty\": true }"), 4, "Hard"));
        assertEquals("encounters:4:hard", EncounterLeaderboardListener.bucketFor(
                group("{ \"Bucket\": \"encounters\", \"ByPartySize\": true, \"ByDifficulty\": true }"), 4, "hard"));
    }

    @Test
    void aRunWithNoDifficultyStillSplitsIntoItsOwnBucket() throws IOException {
        assertEquals("encounters:any",
                EncounterLeaderboardListener.bucketFor(group("{ \"Bucket\": \"encounters\", \"ByDifficulty\": true }"), 2, null));
    }

    @Test
    void noGroupOrNoBucketWritesNothing() throws IOException {
        assertNull(EncounterLeaderboardListener.bucketFor(null, 4, "hard"));
        assertNull(EncounterLeaderboardListener.bucketFor(group("{ \"ByPartySize\": true }"), 4, "hard"),
                "a split with nothing to split is still nothing");
        Leaderboard board = new Leaderboard("test");
        assertEquals(0, EncounterLeaderboardListener.record(board, defeat("hard", 200.0,
                share(ALICE, "alice", 1.0, 900.0, 40.0)), null));
        assertTrue(board.forBucket("encounters").isEmpty());
    }

    // ==================== the rows ====================

    @Test
    void everyParticipantGetsARowWithTheirShareAsScoreAndTheFightsLengthAsTime() throws IOException {
        Leaderboard board = new Leaderboard("test");
        EncounterDefeatedEvent event = defeat("hard", 200.4,
                share(ALICE, "alice", 1.0, 900.0, 40.0), share(BOB, "bob", 0.35, 300.0, 210.0));

        int rows = EncounterLeaderboardListener.record(board, event, group("{ \"Bucket\": \"encounters\" }"));

        assertEquals(2, rows);
        Map<UUID, LeaderboardEntry> bucket = board.forBucket("encounters");
        LeaderboardEntry alice = bucket.get(ALICE);
        LeaderboardEntry bob = bucket.get(BOB);
        assertNotNull(alice);
        assertNotNull(bob);
        assertEquals(100, alice.bestScore, "the top contributor reads 100");
        assertEquals(35, bob.bestScore, "a share as whole percent points");
        assertEquals(200, alice.bestTimeSeconds, "the fight's length, rounded, kept lower on a win");
        assertEquals(900L, alice.stat(EncounterLeaderboardListener.STAT_DAMAGE_DEALT));
        assertEquals(210L, bob.stat(EncounterLeaderboardListener.STAT_DAMAGE_TAKEN));
        assertEquals("bob", bob.name);
        assertEquals(1, bob.plays);
    }

    @Test
    void aSecondFasterDefeatKeepsTheLowerTimeAndAccruesPoints() throws IOException {
        Leaderboard board = new Leaderboard("test");
        EncounterBindingAsset.Leaderboard group = group("{ \"Bucket\": \"encounters\" }");

        EncounterLeaderboardListener.record(board, defeat("hard", 300.0, share(ALICE, "alice", 0.5, 1.0, 0.0)), group);
        EncounterLeaderboardListener.record(board, defeat("hard", 180.0, share(ALICE, "alice", 1.0, 1.0, 0.0)), group);

        LeaderboardEntry alice = board.forBucket("encounters").get(ALICE);
        assertEquals(180, alice.bestTimeSeconds, "fastest clear is free");
        assertEquals(100, alice.bestScore);
        assertEquals(150L, alice.totalPoints(), "points accrue with every fight");
        assertEquals(2, alice.plays);
    }
}
