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
 * record is saved with, and the server's starts through the file they are kept in. A save from before boosts reads
 * as none held.
 */
class BoostPersistenceTest {

    private static final List<BoostRun> RUNS = List.of(
            new BoostRun("Your_Harvest", 50.0, 1_000L, 1_801_000L, "Steve"),
            new BoostRun("Your_Weekend", 12.5, 1_500L, 9_000_000_000_000L, ""));

    @Test
    void aPlayersOwnBoostsRoundTripThroughTheEngineCodec() throws IOException {
        HeldBoosts held = new HeldBoosts(RUNS, Map.of("Your_Harvest", 3_601_000L));
        TrackComponent record = new TrackComponent();
        record.boosts(held);

        ExtraInfo info = new ExtraInfo();
        TrackComponent restarted = TrackComponent.CODEC.decode(TrackComponent.CODEC.encode(record, info), info);
        assertEquals(held, restarted.boosts(), "every start, its percent, both times and its starter, and every wait");
        assertEquals(3_601_000L, restarted.boosts().cooldownUntil("YOUR_HARVEST"), "a wait reads in any case");

        TrackComponent before = TrackComponent.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{\"Tracks\": [ { \"Track\": \"Your_Mining\", \"Value\": 10 } ]}"),
                new ExtraInfo());
        assertEquals(HeldBoosts.EMPTY, before.boosts(), "a player saved before boosts holds none");
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
