package com.ziggfreed.common.track.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.track.TrackComponent;

/**
 * Running boosts survive a restart: a player's own starts and waits read back through the engine codec the player's
 * record is saved with, the server's starts through the file they are kept in, and a boost token's numbers through
 * the codec its stack's metadata is saved with. A save from before boosts reads as none held.
 */
class BoostPersistenceTest {

    private static final List<BoostRun> RUNS = List.of(
            new BoostRun("Your_Harvest", 50.0, 1_000L, 1_801_000L, "Steve"),
            new BoostRun("Your_Weekend", 12.5, 1_500L, 9_000_000_000_000L, ""),
            new BoostRun("Your_Lamp", 100.0, 2_000L, 3_000L, "Alex", "Your_Mining"));

    @Test
    void aPlayersOwnBoostsRoundTripThroughTheEngineCodec() throws IOException {
        HeldBoosts held = new HeldBoosts(RUNS, Map.of("Your_Harvest", 3_601_000L));
        TrackComponent record = new TrackComponent();
        record.boosts(held);

        ExtraInfo info = new ExtraInfo();
        TrackComponent restarted = TrackComponent.CODEC.decode(TrackComponent.CODEC.encode(record, info), info);
        assertEquals(held, restarted.boosts(),
                "every start, its percent, both times, its starter and its one track, and every wait");
        assertEquals(3_601_000L, restarted.boosts().cooldownUntil("YOUR_HARVEST"), "a wait reads in any case");

        TrackComponent before = TrackComponent.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{\"Tracks\": [ { \"Track\": \"Your_Mining\", \"Value\": 10 } ]}"),
                new ExtraInfo());
        assertEquals(HeldBoosts.EMPTY, before.boosts(), "a player saved before boosts holds none");
    }

    @Test
    void aBoostTokenRoundTripsThroughItsStackCodec() throws IOException {
        ExtraInfo info = new ExtraInfo();
        BoostToken full = new BoostToken("Your_Harvest", 75.0, 900L, "Your_Mining");
        assertEquals(full, BoostToken.CODEC.decode(BoostToken.CODEC.encode(full, info), info),
                "the boost, its percent, its length and its one track");
        BoostToken own = new BoostToken("Your_Harvest", null, null, null);
        assertEquals(own, BoostToken.CODEC.decode(BoostToken.CODEC.encode(own, info), info),
                "a token of the boost's own numbers is its boost alone");
        assertEquals(full, BoostToken.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{\"Boost\": \"Your_Harvest\", \"Percent\": 75, \"Seconds\": 900, \"Track\": \"Your_Mining\"}"),
                new ExtraInfo()), "the saved keys a stack carries");
    }

    @Test
    void theServersBoostsRoundTripThroughTheirFile() {
        assertEquals(RUNS, ServerBoosts.decode(ServerBoosts.encode(RUNS)));
        assertEquals(List.of(), ServerBoosts.decode(ServerBoosts.encode(List.of())), "none running reads as none");
        assertEquals(List.of(new BoostRun("Kept", 25.0, 1L, 2L, "")), ServerBoosts.decode(
                "{\"version\": 1, \"runs\": [ {\"percent\": 10}, {\"boost\": \"Kept\", \"percent\": 25, "
                        + "\"startedAt\": 1, \"endsAt\": 2} ]}"),
                "a row naming no boost is left out");
    }
}
