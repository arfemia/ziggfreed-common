package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A retired stat id a stack saved reads under its successors, in full for each, summed with what is
 * already there, so an item stamped before a rename keeps its bonus and one stamped again stores the
 * new ids once.
 */
class StatAliasesTest {

    @AfterEach
    void reset() {
        StatAliases.resetForTests();
    }

    @Test
    void aRetiredIdReadsAsItsSuccessorsInFull() {
        StatAliases.register("Old_Fire", "New_Fire");
        StatAliases.register("Old_Physical", "New_Physical", "New_Projectile");
        Map<String, Double> saved = new LinkedHashMap<>();
        saved.put("old_fire", 12.0);
        saved.put("Old_Physical", 5.0);
        saved.put("Kept", 3.0);
        Map<String, Double> read = StatAliases.normalize(saved);
        assertEquals(List.of("New_Fire", "New_Physical", "New_Projectile", "Kept"), List.copyOf(read.keySet()));
        assertEquals(12.0, read.get("New_Fire"));
        assertEquals(5.0, read.get("New_Physical"));
        assertEquals(5.0, read.get("New_Projectile"));
        assertEquals(3.0, read.get("Kept"));
    }

    @Test
    void aStackHoldingBothTheOldAndTheNewIdSumsOnce() {
        StatAliases.register("Old_Physical", "New_Physical", "New_Projectile");
        Map<String, Double> saved = new LinkedHashMap<>();
        saved.put("Old_Physical", 5.0);
        saved.put("New_Physical", 2.0);
        Map<String, Double> merged = StackStats.merge(saved, Map.of("Old_Physical", 1.0));
        assertEquals(8.0, merged.get("New_Physical"));
        assertEquals(6.0, merged.get("New_Projectile"));
        assertEquals(2, merged.size());
    }

    @Test
    void aScaledAliasCountsTheOldValueTimesItsScale() {
        StatAliases.registerScaled("Old_Flat", 4.0, "New_Percent");
        Map<String, Double> saved = new LinkedHashMap<>();
        saved.put("Old_Flat", 2.0);
        saved.put("New_Percent", 1.0);
        Map<String, Double> read = StatAliases.normalize(saved);
        assertEquals(9.0, read.get("New_Percent"));
        assertEquals(4.0, StatAliases.scale("old_flat"));
        assertEquals(1.0, StatAliases.scale("New_Percent"));
    }

    @Test
    void nothingRetiredLeavesTheMapAsItIs() {
        StatAliases.register("Old_Fire", "New_Fire");
        Map<String, Double> saved = Map.of("Kept", 3.0);
        assertSame(saved, StatAliases.normalize(saved));
        assertEquals(List.of("Kept"), StatAliases.resolve("Kept"));
    }
}
