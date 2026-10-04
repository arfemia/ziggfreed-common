package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.counter.CounterMap;

/** The one key scheme every tally is filed under, and the names it cannot carry. */
class AlmanacKeysTest {

    @Test
    void aTallyIsFiledUnderItsSeasonAndName() {
        assertEquals("test_season/bombs_thrown", AlmanacKeys.lifetime("Test_Season", "Bombs_Thrown"));
        assertEquals("test_season@2026/bombs_thrown", AlmanacKeys.season("Test_Season", 2026, "Bombs_Thrown"));
        assertEquals("test_season@2026/$attended", AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED));
    }

    @Test
    void aNameTheRecordFormatReservesIsNotUsable() {
        assertTrue(AlmanacKeys.usableId("Bombs_Thrown"));
        for (String bad : new String[] {"a/b", "a@b", "a|b", "a:b", "$hidden", " ", ""}) {
            assertFalse(AlmanacKeys.usableId(bad), "'" + bad + "' must not be usable");
        }
        assertFalse(AlmanacKeys.usableId(null));
    }

    @Test
    void theSeasonsARecordHoldsAreReadBackOldestFirst() {
        CounterMap tallies = new CounterMap();
        tallies.add("hallows_eve@2027/$attended", 1L);
        tallies.add("hallows_eve@2026/bombs", 4L);
        tallies.add("hallows_eve/bombs", 9L);
        tallies.add("hallows@2020/bombs", 1L);
        tallies.add("hallows_eve@soon/bombs", 1L);

        assertEquals(List.of(2026, 2027), List.copyOf(AlmanacKeys.seasonYears(tallies, "Hallows_Eve")),
                "only well-formed season keys of this event count, never a prefix-sharing neighbour");
    }
}
