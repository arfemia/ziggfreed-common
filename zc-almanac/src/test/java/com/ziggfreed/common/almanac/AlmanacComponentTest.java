package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.counter.CounterMap;

/** The record's save format: one {@code key:value|...} blob that only ever grows. */
class AlmanacComponentTest {

    @Test
    void theRecordRoundTripsEveryTally() {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.lifetime("test_season", "bombs_thrown"), 120L);
        tallies.add(AlmanacKeys.season("test_season", 2026, "bombs_thrown"), 42L);
        tallies.add(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED), 1L);

        CounterMap back = AlmanacComponent.deserialize(AlmanacComponent.serialize(tallies.all()));

        assertEquals(tallies.all(), back.all());
    }

    @Test
    void anEmptyRecordIsAnEmptyString() {
        assertEquals("", AlmanacComponent.serialize(Map.of()));
        assertTrue(AlmanacComponent.deserialize("").isEmpty());
        assertTrue(AlmanacComponent.deserialize(null).isEmpty());
    }

    @Test
    void aDamagedPairCostsItselfAndNoOtherTally() {
        CounterMap back = AlmanacComponent.deserialize("test_season/a:3|garbage|test_season/b:x|test_season/c:5");

        assertEquals(Map.of("test_season/a", 3L, "test_season/c", 5L), back.all());
    }

    @Test
    void aCopyIsIndependentOfItsOriginal() {
        AlmanacComponent original = new AlmanacComponent();
        original.tallies.add("test_season/a", 1L);

        AlmanacComponent copy = original.clone();
        copy.tallies.add("test_season/a", 1L);

        assertEquals(1L, original.tallies.get("test_season/a"));
        assertEquals(2L, copy.tallies.get("test_season/a"));
    }
}
