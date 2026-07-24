package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pure key-scheme + diff/sweep math coverage for {@link EquipStatBridge#plan}, against a FAKE
 * seam (a hand-rolled index resolver + existing-amount lookup standing in for the live {@link
 * com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap}) - never a live server.
 */
class EquipStatBridgeTest {

    private static final int STAT_MAP_SIZE = 5;

    /** index 0 -> "StatA", 1 -> "StatB", 2 -> "StatC"; everything else unregistered (-1). */
    private static int indexOf(String statId) {
        return switch (statId) {
            case "StatA" -> 0;
            case "StatB" -> 1;
            case "StatC" -> 2;
            default -> -1;
        };
    }

    @Test
    void newEntryProducesAPut() {
        Map<String, Double> entries = Map.of("StatA", 10.0);
        EquipStatBridge.Plan plan = EquipStatBridge.plan(entries, null, "ns",
                EquipStatBridgeTest::indexOf, idx -> null, STAT_MAP_SIZE, id -> { });

        assertEquals(Map.of(0, 10.0f), plan.puts);
        // every OTHER known index (1..STAT_MAP_SIZE-1) sweeps since it was never touched
        assertEquals(Set.of(1, 2, 3, 4), plan.removes);
    }

    @Test
    void unchangedEntryIsDiffSkipped() {
        Map<String, Double> entries = Map.of("StatA", 10.0);
        // existing amount already equals the new amount at index 0
        EquipStatBridge.Plan plan = EquipStatBridge.plan(entries, null, "ns",
                EquipStatBridgeTest::indexOf, idx -> idx == 0 ? 10.0f : null, STAT_MAP_SIZE, id -> { });

        assertTrue(plan.puts.isEmpty());
        // index 0 is TOUCHED (matched, just unchanged) so it must NOT be swept
        assertFalse(plan.removes.contains(0));
        assertEquals(Set.of(1, 2, 3, 4), plan.removes);
    }

    @Test
    void changedEntryProducesAPutOverTheOldAmount() {
        Map<String, Double> entries = Map.of("StatA", 15.0);
        EquipStatBridge.Plan plan = EquipStatBridge.plan(entries, null, "ns",
                EquipStatBridgeTest::indexOf, idx -> idx == 0 ? 10.0f : null, STAT_MAP_SIZE, id -> { });

        assertEquals(Map.of(0, 15.0f), plan.puts);
        assertFalse(plan.removes.contains(0));
    }

    @Test
    void statNoLongerPresentIsSwept() {
        // last apply touched index 0 (StatA); this apply has NO entries at all
        EquipStatBridge.Plan plan = EquipStatBridge.plan(Map.of(), null, "ns",
                EquipStatBridgeTest::indexOf, idx -> idx == 0 ? 10.0f : null, STAT_MAP_SIZE, id -> { });

        assertTrue(plan.puts.isEmpty());
        assertEquals(Set.of(0, 1, 2, 3, 4), plan.removes);
    }

    @Test
    void unknownStatIdIsSkippedAndReported() {
        Map<String, Double> entries = Map.of("UnknownStat", 5.0);
        Set<String> reported = new HashSet<>();
        EquipStatBridge.Plan plan = EquipStatBridge.plan(entries, null, "ns",
                EquipStatBridgeTest::indexOf, idx -> null, STAT_MAP_SIZE, reported::add);

        assertTrue(plan.puts.isEmpty());
        assertEquals(Set.of("UnknownStat"), reported);
        // nothing was touched, so every index sweeps
        assertEquals(Set.of(0, 1, 2, 3, 4), plan.removes);
    }

    @Test
    void entryFilterExcludesAChannelAndItSweepsToo() {
        Map<String, Double> entries = new LinkedHashMap<>();
        entries.put("StatA", 10.0);
        entries.put("StatB", 20.0);
        EquipStatBridge.EntryFilter filter = (ns, statId) -> !"StatB".equals(statId);

        EquipStatBridge.Plan plan = EquipStatBridge.plan(entries, filter, "ns",
                EquipStatBridgeTest::indexOf, idx -> null, STAT_MAP_SIZE, id -> { });

        assertEquals(Map.of(0, 10.0f), plan.puts);
        // StatB (index 1) was filtered OUT entirely - never touched, so it sweeps
        assertTrue(plan.removes.contains(1));
    }

    @Test
    void multipleStatsFromOneSourceEachGetTheirOwnIndex() {
        Map<String, Double> entries = new LinkedHashMap<>();
        entries.put("StatA", 5.0);
        entries.put("StatB", 7.0);
        entries.put("StatC", 9.0);

        EquipStatBridge.Plan plan = EquipStatBridge.plan(entries, null, "ns",
                EquipStatBridgeTest::indexOf, idx -> null, STAT_MAP_SIZE, id -> { });

        assertEquals(new HashMap<>(Map.of(0, 5.0f, 1, 7.0f, 2, 9.0f)), plan.puts);
        assertEquals(Set.of(3, 4), plan.removes);
    }

    @Test
    void nullKeyOrValueEntryIsIgnored() {
        Map<String, Double> entries = new HashMap<>();
        entries.put("StatA", 10.0);
        entries.put(null, 1.0);

        EquipStatBridge.Plan plan = EquipStatBridge.plan(entries, null, "ns",
                EquipStatBridgeTest::indexOf, idx -> null, STAT_MAP_SIZE, id -> { });

        assertEquals(Map.of(0, 10.0f), plan.puts);
    }
}
