package com.ziggfreed.common.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * Mechanics of the tally framework: the two ways to move a counter (accumulate versus raise a
 * ceiling), the drop-at-zero rule, and the category key scheme that lets one flat store carry a
 * grand total beside any number of per-thing breakdowns.
 */
class CountersTest {

    private static final Subject ALICE = new Subject(new UUID(0, 1), "Alice", null);
    private static final Subject BOB = new Subject(new UUID(0, 2), "Bob", null);

    @Test
    void addAccumulatesAndHighWaterRaisesACeiling() {
        Counters counters = new Counters();

        assertEquals(1L, counters.increment(ALICE, "runs"));
        assertEquals(4L, counters.add(ALICE, "runs", 3L));
        assertEquals(4L, counters.add(ALICE, "runs", 0L), "a zero delta touches nothing");

        assertTrue(counters.highWater(ALICE, "best", 5L), "the first value raises the ceiling");
        assertFalse(counters.highWater(ALICE, "best", 4L), "a lower value never lowers it");
        assertEquals(5L, counters.get(ALICE, "best"),
                "a run of 5 then a run of 4 leaves the best at 5, not 9");
        assertTrue(counters.highWater(ALICE, "best", 9L));
        assertEquals(9L, counters.get(ALICE, "best"));
    }

    @Test
    void subjectsDoNotShareTallies() {
        Counters counters = new Counters();
        counters.add(ALICE, "runs", 7L);
        assertEquals(7L, counters.get(ALICE, "runs"));
        assertEquals(0L, counters.get(BOB, "runs"), "an untouched subject reads zero, never an error");
    }

    @Test
    void writingZeroDropsTheKeyRatherThanStoringAZero() {
        Counters counters = new Counters();
        counters.add(ALICE, "runs", 3L);
        counters.set(ALICE, "runs", 0L);

        assertEquals(0L, counters.get(ALICE, "runs"));
        assertTrue(counters.all(ALICE).isEmpty(), "a reset leaves no entry behind");
    }

    @Test
    void categoryKeysGroupWithoutASchema() {
        Counters counters = new Counters();
        counters.increment(ALICE, "broken");
        counters.increment(ALICE, "broken", "stone");
        counters.add(ALICE, "broken", "stone", 4L);
        counters.increment(ALICE, "broken", "wood");
        counters.increment(ALICE, "placed", "wood");

        assertEquals(Map.of("stone", 5L, "wood", 1L), counters.category(ALICE, "broken"),
                "a category view strips the prefix and covers only its own keys");
        assertEquals(Map.of("wood", 1L), counters.category(ALICE, "placed"));
        assertEquals(Map.of("broken", 1L), counters.totals(ALICE),
                "the grand total is a plain key and never leaks into a category view");
        assertEquals(java.util.Set.of("broken", "placed"), counters.categories(ALICE));
    }

    @Test
    void theCategorySeparatorIsReservedInBothHalves() {
        assertEquals("broken/stone", Counters.key("broken", "stone"));
        assertEquals("stone", Counters.key(null, "stone"), "a blank category yields the bare name");
        assertEquals("stone", Counters.key("  ", "stone"));

        assertTrue(Counters.isReservedName("broken/stone"));
        assertTrue(Counters.isReservedName(""));
        assertTrue(Counters.isReservedName(null));
        assertFalse(Counters.isReservedName("broken"));
    }

    @Test
    void addAllAppliesABagOfDeltasAndSnapshotMerges() {
        Counters counters = new Counters();
        counters.addAll(ALICE, Map.of("runs", 2L, "broken/stone", 3L));
        counters.addAll(ALICE, Map.of("runs", 5L));

        assertEquals(7L, counters.get(ALICE, "runs"));
        assertEquals(3L, counters.get(ALICE, "broken", "stone"));

        CounterMap snapshot = counters.snapshot(ALICE);
        assertEquals(7L, snapshot.get("runs"));
        assertEquals(3L, snapshot.get("broken/stone"), "a snapshot keeps keys exactly as stored");
    }

    @Test
    void clearForgetsEverythingForOneSubjectOnly() {
        Counters counters = new Counters();
        counters.add(ALICE, "runs", 3L);
        counters.add(BOB, "runs", 4L);

        counters.clear(ALICE);
        assertTrue(counters.all(ALICE).isEmpty());
        assertEquals(4L, counters.get(BOB, "runs"));
    }
}
