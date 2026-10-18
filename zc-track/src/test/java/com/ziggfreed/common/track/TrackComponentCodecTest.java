package com.ziggfreed.common.track;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The per-player record of climbs, read back through the engine codec a restart reads it with: every leaf of
 * every key (a reserved namespace and run included) survives, a climb with nothing on record is not saved, a
 * key reads in any case, and a save written with fewer leaves reads the rest as nothing on record.
 */
class TrackComponentCodecTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void everyClimbRoundTripsThroughTheEngineCodec() throws IOException {
        TrackKey mining = TrackKey.player(PLAYER, "Your_Mining");
        TrackKey seasonal = new TrackKey(TrackKey.Subject.player(PLAYER), "Your_Ruleset", "Your_Mining",
                "Your_Event@2026");
        TrackRecord climbed = new TrackRecord(123_456_789_012L, 0.375, 150, 2,
                Map.of("at_25_50_75", 25, "every_15", 0, "your_cache", 100));
        TrackRecord lost = new TrackRecord(-500L, 0.0, 3, 0, Map.of());
        TrackComponent record = new TrackComponent();
        record.put(mining, climbed);
        record.put(seasonal, lost);
        record.put(TrackKey.player(PLAYER, "Your_Untouched"), TrackRecord.EMPTY);

        ExtraInfo info = new ExtraInfo();
        TrackComponent restarted = TrackComponent.CODEC.decode(TrackComponent.CODEC.encode(record, info), info);
        assertEquals(climbed, restarted.get(mining), "the value, its fraction, the best, the prestige and the marks");
        assertEquals(lost, restarted.get(seasonal), "a namespaced, seasonal climb is its own entry");
        assertEquals(2, restarted.size(), "a climb with nothing on record is not saved");
        assertEquals(climbed, restarted.get(TrackKey.player(PLAYER, "YOUR_MINING")), "a key reads in any case");
        assertEquals(List.of("your_mining"), restarted.defaultTracks());

        TrackComponent fewer = TrackComponent.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{\"Tracks\": [ { \"Track\": \"Your_Mining\", \"Value\": 10 } ]}"), new ExtraInfo());
        assertEquals(new TrackRecord(10L, 0.0, 0, 0, Map.of()), fewer.get(mining),
                "a leaf the save does not carry reads as nothing on record");

        TrackComponent before = TrackComponent.CODEC.decodeJson(RawJsonReader.fromJsonString("{}"), new ExtraInfo());
        assertEquals(TrackRecord.EMPTY, before.get(mining), "a player saved before the record existed holds nothing");
    }
}
