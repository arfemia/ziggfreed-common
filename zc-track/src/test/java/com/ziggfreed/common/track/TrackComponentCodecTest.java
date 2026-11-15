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
 * every key (a reserved namespace and run included, and the picks made on a climb) survives, a climb with nothing on
 * record is not saved, a key reads in any case, and a save written with fewer leaves reads the rest as nothing on
 * record.
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
        TrackKey swords = TrackKey.player(PLAYER, "Your_Swords");
        TrackRecord picked = new TrackRecord(9_000L, 0.0, 12, 1, Map.of()).withPicks(new PickState(
                Map.of("your_swords_lines/lines/2", List.of("Your_Edge"),
                        "your_swords_lines/lines/3", List.of("your_keen", "your_edge")),
                Map.of("Your_Whirlwind", 2),
                Map.of("your_swords_lines/lines/4", List.of("your_precision"))));
        TrackComponent record = new TrackComponent();
        record.put(mining, climbed);
        record.put(seasonal, lost);
        record.put(swords, picked);
        record.put(TrackKey.player(PLAYER, "Your_Untouched"), TrackRecord.EMPTY);

        ExtraInfo info = new ExtraInfo();
        TrackComponent restarted = TrackComponent.CODEC.decode(TrackComponent.CODEC.encode(record, info), info);
        assertEquals(climbed, restarted.get(mining), "the value, its fraction, the best, the prestige and the marks");
        assertEquals(lost, restarted.get(seasonal), "a namespaced, seasonal climb is its own entry");
        assertEquals(picked, restarted.get(swords), "the picks made, the takes and what a prestige put aside");
        assertEquals(List.of("your_keen", "your_edge"), restarted.get(swords).picks()
                .chosen("your_swords_lines/lines/3"), "a slot's options keep the order they were chosen in");
        assertEquals(3, restarted.size(), "a climb with nothing on record is not saved");
        assertEquals(climbed, restarted.get(TrackKey.player(PLAYER, "YOUR_MINING")), "a key reads in any case");
        assertEquals(List.of("your_mining", "your_swords"), restarted.defaultTracks());

        TrackComponent fewer = TrackComponent.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{\"Tracks\": [ { \"Track\": \"Your_Mining\", \"Value\": 10 } ]}"), new ExtraInfo());
        assertEquals(new TrackRecord(10L, 0.0, 0, 0, Map.of()), fewer.get(mining),
                "a leaf the save does not carry reads as nothing on record");

        TrackComponent before = TrackComponent.CODEC.decodeJson(RawJsonReader.fromJsonString("{}"), new ExtraInfo());
        assertEquals(TrackRecord.EMPTY, before.get(mining), "a player saved before the record existed holds nothing");
    }

    /**
     * The one {@code Build} leaf: the order every take was made in, the takes a Remember prestige put aside in order,
     * the saved builds and the build in use all survive a restart, and the leaf reads back from its written shape (a
     * take's count read off the order, a slot's options a string array).
     */
    @Test
    void savedBuildsAndTakeOrderRoundTrip() throws IOException {
        TrackKey swords = TrackKey.player(PLAYER, "Your_Swords");
        PickState.Loadout fencer = new PickState.Loadout(Map.of("your_swords_lines/lines/2", List.of("your_edge")),
                List.of("Your_Keen_Edge", "Your_Keen_Edge", "Your_Flow"));
        PickState state = PickState.EMPTY
                .withTakes("Your_Planted", 1)
                .withTakes("Your_Keen_Edge", 2)
                .withTakes("Your_Planted", 1)
                .withRememberedTakes(List.of("Your_Reaper", "Your_Whirl", "Your_Reaper"))
                .withLoadout(1, fencer)
                .withLoadout(2, new PickState.Loadout(Map.of(), List.of("Your_Planted")))
                .withActiveLoadout(1);
        TrackRecord built = new TrackRecord(9_000L, 0.0, 30, 0, Map.of()).withPicks(state);
        TrackComponent record = new TrackComponent();
        record.put(swords, built);

        ExtraInfo info = new ExtraInfo();
        TrackRecord restarted = TrackComponent.CODEC.decode(TrackComponent.CODEC.encode(record, info), info)
                .get(swords);
        assertEquals(built, restarted, "the takes in order, what a prestige put aside, the builds and the one in use");
        assertEquals(List.of("your_planted", "your_keen_edge", "your_keen_edge", "your_planted"),
                restarted.picks().order(), "every take keeps the order it was made in");
        assertEquals(List.of("your_reaper", "your_whirl", "your_reaper"), restarted.picks().rememberedTakes());
        assertEquals(List.of("your_keen_edge", "your_keen_edge", "your_flow"), restarted.picks().loadout(1).takes(),
                "a saved build keeps its takes in order");
        assertEquals(1, restarted.picks().activeLoadout());

        TrackComponent written = TrackComponent.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{\"Tracks\": [ { \"Track\": \"Your_Swords\", \"Build\": {"
                        + " \"Choices\": { \"your_swords_lines/lines/2\": [ \"Your_Keen\", \"Your_Edge\" ] },"
                        + " \"Order\": [ \"Your_B\", \"your_swords_lines/lines/2\", \"Your_A\", \"Your_B\" ],"
                        + " \"Remembered\": { \"Takes\": [ \"Your_C\" ] },"
                        + " \"Loadouts\": [ { \"Slot\": 2, \"Order\": [ \"Your_A\" ] } ], \"Active\": 2 } } ]}"),
                new ExtraInfo());
        PickState read = written.get(swords).picks();
        assertEquals(2, read.takesOf("your_b"), "a take's count is read off the order");
        assertEquals(List.of("your_b", "your_swords_lines/lines/2", "your_a", "your_b"), read.order());
        assertEquals(List.of("your_keen", "your_edge"), read.chosen("your_swords_lines/lines/2"));
        assertEquals(List.of("your_c"), read.rememberedTakes());
        assertEquals(List.of("your_a"), read.loadout(2).takes());
        assertEquals(2, read.activeLoadout());
    }
}
