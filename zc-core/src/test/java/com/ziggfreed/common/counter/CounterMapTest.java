package com.ziggfreed.common.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Arithmetic and merge semantics of the owner-free tally bag. */
class CounterMapTest {

    @Test
    void absentKeysReadZeroAndZeroValuesAreNeverStored() {
        CounterMap map = new CounterMap();
        assertEquals(0L, map.get("nothing"));
        assertEquals(0L, map.get(null));
        assertTrue(map.isEmpty());

        map.add("runs", 3L);
        map.set("runs", 0L);
        assertTrue(map.isEmpty(), "a key written to zero is dropped, not stored as a zero");
    }

    @Test
    void mergeSumsAccumulatesWhileMergeHighWaterKeepsTheHigher() {
        CounterMap left = CounterMap.of(Map.of("runs", 3L, "best", 5L));
        CounterMap right = CounterMap.of(Map.of("runs", 4L, "best", 4L));

        CounterMap summed = left.copy();
        summed.mergeSums(right);
        assertEquals(7L, summed.get("runs"));
        assertEquals(9L, summed.get("best"), "mergeSums adds every key, including one meant as a best");

        CounterMap best = left.copy();
        best.mergeHighWater(right);
        assertEquals(5L, best.get("best"), "mergeHighWater keeps the higher of the two");
        assertEquals(4L, best.get("runs"), "and applies the same rule to every key it is given");
    }

    @Test
    void copyIsIndependent() {
        CounterMap original = CounterMap.of(Map.of("runs", 3L));
        CounterMap copy = original.copy();
        copy.add("runs", 10L);

        assertEquals(3L, original.get("runs"), "editing a copy never reaches the original");
        assertEquals(13L, copy.get("runs"));
    }

    @Test
    void highWaterReportsOnlyTheCallThatRaisedIt() {
        CounterMap map = new CounterMap();
        assertTrue(map.highWater("best", 5L));
        assertFalse(map.highWater("best", 5L), "an equal value is not a rise");
        assertFalse(map.highWater("best", 1L));
        assertTrue(map.highWater("best", 6L));
    }

    @Test
    void aKeyMatchesWhateverItsCaseAndAWriteAdoptsTheWritersSpelling() {
        CounterMap map = CounterMap.of(Map.of("mob_kills", 5L));

        assertEquals(5L, map.get("Mob_Kills"), "a read under another casing finds the same tally");
        assertEquals(5L, map.get("MOB_KILLS"));
        assertEquals(1, map.size(), "and it is one tally, not one per spelling");

        assertEquals(6L, map.add("Mob_Kills", 1L));
        assertEquals(Map.of("Mob_Kills", 6L), map.all(),
                "the write re-spells the entry the writer's way, which is how a key loaded under an"
                        + " older casing takes the current one on its next write");
        assertEquals(6L, map.get("mob_kills"), "and the old spelling still reads it");

        assertTrue(map.highWater("MOB_KILLS", 9L));
        assertEquals(Map.of("MOB_KILLS", 9L), map.all(), "a raised ceiling re-spells it too");
        assertTrue(map.remove("mob_kills"), "and a removal finds it under any casing");
        assertTrue(map.isEmpty());
    }

    @Test
    void anEntryNobodyWritesAgainKeepsTheSpellingItWasLoadedWith() {
        CounterMap map = CounterMap.of(Map.of("mob_kills", 5L, "deaths", 2L));

        map.add("Mob_Kills", 1L);

        assertEquals(Map.of("Mob_Kills", 6L, "deaths", 2L), map.all(),
                "only the key that was written changes spelling; the rest of the bag is untouched");
    }

    @Test
    void allIsAnUnmodifiableSnapshot() {
        CounterMap map = CounterMap.of(Map.of("runs", 2L));
        Map<String, Long> snapshot = map.all();
        map.add("runs", 5L);
        assertEquals(2L, snapshot.get("runs"), "a snapshot does not follow later edits");
        assertEquals(7L, map.get("runs"));
    }
}
