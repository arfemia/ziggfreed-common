package com.ziggfreed.common.instance.dungeon.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

/**
 * The room logic's two pure rewrites over a volume's decoded JSON (PD3): a key authored {@code @room} or {@code @run}
 * becomes that room's or run's own token on every kind of key a volume names one in, so two copies of a room or two
 * parties never answer each other; and a {@code ZigVariant} group keeps the one variant its run picked, the same in
 * every room. Untagged: nothing here builds an engine item.
 */
class RoomScopeTest {

    private static final String VOLUME = """
            {
              "Name": "Lever",
              "Tags": {"Door@room": "Closed", "Lever@run": "Off", "State": "Ready", "Bars": "Gate@room"},
              "Effects": [
                {"Type": "SendSignal", "Event": "BLOCK_USED", "MatchKey": "Door@room", "MatchValue": "Open@run",
                 "SignalKeys": ["Solved@room", "Opened@run", "Plain"], "SignalValues": ["Yes@room"]},
                {"Type": "ModifyTags", "Event": "BLOCK_USED", "MatchKey": "Lever@run=Off", "TagKey": "Power@room",
                 "TagValue": "On"}
              ],
              "Conditions": [
                {"Type": "TagCondition", "Event": "SIGNAL_RECEIVED", "Source": "Radius", "TagKey": "Lit@ROOM",
                 "CompareTagKey": "Want@room", "MatchKey": "Brazier@room", "TagValue": "Yes"}
              ]
            }
            """;

    @Test
    void everyKeyKindTakesItsRoomsOrRunsToken() {
        BsonDocument authored = BsonDocument.parse(VOLUME);
        BsonDocument scoped = RoomKeys.rewrite(authored, RoomKeys.tokens(2, 5, 7));

        BsonDocument tags = scoped.getDocument("Tags");
        assertEquals("Closed", tags.getString("Door@2.5.7").getValue(), "a Tags key, @room");
        assertEquals("Off", tags.getString("Lever@2.5").getValue(), "a Tags key, @run");
        assertEquals("Ready", tags.getString("State").getValue(), "a plain key stays world-wide");
        assertEquals("Gate@2.5.7", tags.getString("Bars").getValue(), "a Tags value, matched in a Key=Value form");
        assertEquals(4, tags.size());

        BsonDocument signal = scoped.getArray("Effects").get(0).asDocument();
        assertEquals("Door@2.5.7", signal.getString("MatchKey").getValue(), "MatchKey");
        assertEquals("Open@2.5", signal.getString("MatchValue").getValue(), "MatchValue");
        assertEquals(List.of("Solved@2.5.7", "Opened@2.5", "Plain"), strings(signal.getArray("SignalKeys")),
                "SignalKeys, a plain one left alone");
        assertEquals(List.of("Yes@2.5.7"), strings(signal.getArray("SignalValues")), "SignalValues");
        assertEquals("SendSignal", signal.getString("Type").getValue(), "a Type is no key");

        BsonDocument modify = scoped.getArray("Effects").get(1).asDocument();
        assertEquals("Lever@2.5=Off", modify.getString("MatchKey").getValue(), "a Key=Value filter, its key scoped");
        assertEquals("Power@2.5.7", modify.getString("TagKey").getValue(), "TagKey");
        assertEquals("On", modify.getString("TagValue").getValue());

        BsonDocument condition = scoped.getArray("Conditions").get(0).asDocument();
        assertEquals("Lit@2.5.7", condition.getString("TagKey").getValue(), "the suffix is read without regard to case");
        assertEquals("Want@2.5.7", condition.getString("CompareTagKey").getValue(), "CompareTagKey");
        assertEquals("Brazier@2.5.7", condition.getString("MatchKey").getValue(), "a condition's MatchKey");

        assertEquals(BsonDocument.parse(VOLUME), authored, "the authored JSON is left as it was");
    }

    @Test
    void twoCopiesOfARoomAndTwoPartiesNeverShareAToken() {
        RoomKeys.Tokens room3 = RoomKeys.tokens(0, 1, 3);
        RoomKeys.Tokens room4 = RoomKeys.tokens(0, 1, 4);
        RoomKeys.Tokens otherLane = RoomKeys.tokens(1, 1, 3);
        RoomKeys.Tokens nextRun = RoomKeys.tokens(0, 2, 3);
        assertNotEquals(room3.scoped("Door@room"), room4.scoped("Door@room"), "two rooms of one run");
        assertEquals(room3.scoped("Key@run"), room4.scoped("Key@run"), "one run shares its @run keys");
        assertNotEquals(room3.scoped("Door@room"), otherLane.scoped("Door@room"), "two parties' lanes");
        assertNotEquals(room3.scoped("Key@run"), otherLane.scoped("Key@run"), "two parties' runs");
        assertNotEquals(room3.scoped("Door@room"), nextRun.scoped("Door@room"), "a lane's next run");
        assertNotEquals(room3.scoped("Door@room"), room3.scoped("Key@run"), "a room token is never a run token");
        assertTrue(RoomKeys.isScoped("Door@room=Open") && !RoomKeys.isScoped("Door") && !RoomKeys.isScoped("@room"));
    }

    @Test
    void oneGroupPicksTheSameVariantInEveryRoom() {
        List<BsonDocument> puzzle = List.of(tagged("Path:1"), tagged("Path:2"), tagged("Path:3"), untagged());
        List<BsonDocument> clue = List.of(tagged("path:1"), tagged("Path:2"), tagged(" Path : 3 "), tagged("Order:1"),
                tagged("Order:2"));
        Set<Integer> seen = new HashSet<>();
        for (long seed = 0; seed < 64; seed++) {
            Map<String, Integer> counts = new HashMap<>();
            for (BsonDocument volume : puzzle) {
                RoomVariants.count(RoomVariants.of(volume), counts);
            }
            for (BsonDocument volume : clue) {
                RoomVariants.count(RoomVariants.of(volume), counts);
            }
            assertEquals(Map.of("path", 3, "order", 2), counts, "a group counts its highest variant over every room");
            Map<String, Integer> picks = RoomVariants.picks(seed, counts);

            List<BsonDocument> keptPuzzle = kept(puzzle, picks);
            List<BsonDocument> keptClue = kept(clue, picks);
            assertEquals(2, keptPuzzle.size(), "one Path variant and the untagged volume");
            assertNull(RoomVariants.of(keptPuzzle.get(1)), "untagged logic is always placed");
            assertEquals(2, keptClue.size(), "one Path variant and one Order variant");
            int puzzlePath = RoomVariants.of(keptPuzzle.get(0)).n();
            int cluePath = RoomVariants.of(keptClue.get(0)).n();
            assertEquals(puzzlePath, cluePath, "the puzzle and its clue agree on the Path, seed " + seed);
            assertEquals(picks.get("path"), puzzlePath);
            assertEquals(RoomVariants.pick(seed, "PATH", 3), puzzlePath, "the pick reads the group without case");
            seen.add(puzzlePath);
        }
        assertEquals(Set.of(1, 2, 3), seen, "the run's seed picks among every variant");
        assertNull(RoomVariants.parse("Path"), "a tag that does not read as <Group>:<n> is no variant");
        assertNull(RoomVariants.parse("Path:0"), "variants count from 1");
    }

    @Nonnull
    private static List<BsonDocument> kept(@Nonnull List<BsonDocument> volumes, @Nonnull Map<String, Integer> picks) {
        List<BsonDocument> out = new ArrayList<>();
        for (BsonDocument volume : volumes) {
            if (RoomVariants.keeps(RoomVariants.of(volume), picks)) {
                out.add(volume);
            }
        }
        return out;
    }

    @Nonnull
    private static BsonDocument tagged(@Nonnull String variant) {
        return new BsonDocument("Tags", new BsonDocument(RoomVariants.KEY, new BsonString(variant))
                .append("Door@room", new BsonString("Closed")));
    }

    @Nonnull
    private static BsonDocument untagged() {
        return new BsonDocument("Tags", new BsonDocument("Door@room", new BsonString("Closed")));
    }

    @Nonnull
    private static List<String> strings(@Nonnull BsonArray array) {
        List<String> out = new ArrayList<>();
        for (BsonValue value : array) {
            out.add(value.asString().getValue());
        }
        return out;
    }
}
