package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Pure key-scheme + diff/sweep math coverage for {@link EquipStatBridge#plan} (the {@link
 * StackStats}-map sources) AND {@link EquipStatBridge#planUtility} (the native {@code
 * Utility.StatModifiers} source), against a FAKE seam (a hand-rolled index resolver /
 * existing-modifier lookup standing in for the live {@link
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

    // ==================== native Utility.StatModifiers (planUtility) ====================

    private static final String UTIL_PREFIX = "ns:util:";

    /** A fake existing-modifier seam keyed by "statIndex|key". */
    private static EquipStatBridge.UtilityPlan planUtility(Int2ObjectMap<StaticModifier[]> mods,
            Map<String, StaticModifier> existing) {
        return EquipStatBridge.planUtility(mods, UTIL_PREFIX, STAT_MAP_SIZE,
                (idx, key) -> existing.get(idx + "|" + key));
    }

    private static StaticModifier additive(float amount) {
        return new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, amount);
    }

    @Test
    void planUtility_newModifierProducesAPutCarryingTheExactStaticModifier() {
        StaticModifier m = additive(12.0f);
        Int2ObjectMap<StaticModifier[]> mods = new Int2ObjectOpenHashMap<>();
        mods.put(1, new StaticModifier[] { m });

        EquipStatBridge.UtilityPlan plan = planUtility(mods, Map.of());

        assertEquals(1, plan.puts.size());
        EquipStatBridge.UtilityPut put = plan.puts.get(0);
        assertEquals(1, put.statIndex);
        assertEquals("ns:util:0", put.key);
        // FULL fidelity: the SAME modifier instance passes straight through (no copy/translation).
        assertSame(m, put.modifier);
        // every OTHER stat index sweeps its util keys (none present on the fake seam -> no removes)
        assertTrue(plan.removes.isEmpty());
    }

    @Test
    void planUtility_preservesMinTargetAndMultiplicativeFidelity() {
        StaticModifier minMult = new StaticModifier(Modifier.ModifierTarget.MIN,
                StaticModifier.CalculationType.MULTIPLICATIVE, 1.5f);
        Int2ObjectMap<StaticModifier[]> mods = new Int2ObjectOpenHashMap<>();
        mods.put(2, new StaticModifier[] { minMult });

        EquipStatBridge.UtilityPlan plan = planUtility(mods, Map.of());

        assertEquals(1, plan.puts.size());
        StaticModifier out = plan.puts.get(0).modifier;
        assertEquals(Modifier.ModifierTarget.MIN, out.getTarget());
        assertEquals(StaticModifier.CalculationType.MULTIPLICATIVE, out.getCalculationType());
        assertEquals(1.5f, out.getAmount());
    }

    @Test
    void planUtility_multipleModifiersPerStatIndexGetOffsetKeys() {
        StaticModifier a = additive(5.0f);
        StaticModifier b = new StaticModifier(Modifier.ModifierTarget.MAX,
                StaticModifier.CalculationType.MULTIPLICATIVE, 2.0f);
        Int2ObjectMap<StaticModifier[]> mods = new Int2ObjectOpenHashMap<>();
        mods.put(0, new StaticModifier[] { a, b });

        EquipStatBridge.UtilityPlan plan = planUtility(mods, Map.of());

        assertEquals(2, plan.puts.size());
        assertEquals("ns:util:0", plan.puts.get(0).key);
        assertSame(a, plan.puts.get(0).modifier);
        assertEquals("ns:util:1", plan.puts.get(1).key);
        assertSame(b, plan.puts.get(1).modifier);
    }

    @Test
    void planUtility_unchangedModifierIsDiffSkipped() {
        StaticModifier m = additive(9.0f);
        Int2ObjectMap<StaticModifier[]> mods = new Int2ObjectOpenHashMap<>();
        mods.put(1, new StaticModifier[] { m });
        // an equal (by value) modifier already sits under (1, "ns:util:0")
        Map<String, StaticModifier> existing = Map.of("1|ns:util:0", additive(9.0f));

        EquipStatBridge.UtilityPlan plan = planUtility(mods, existing);

        assertTrue(plan.puts.isEmpty());
        assertTrue(plan.removes.isEmpty());
    }

    @Test
    void planUtility_changedModifierProducesAPutOverTheOld() {
        Int2ObjectMap<StaticModifier[]> mods = new Int2ObjectOpenHashMap<>();
        mods.put(1, new StaticModifier[] { additive(20.0f) });
        Map<String, StaticModifier> existing = Map.of("1|ns:util:0", additive(9.0f));

        EquipStatBridge.UtilityPlan plan = planUtility(mods, existing);

        assertEquals(1, plan.puts.size());
        assertEquals(20.0f, plan.puts.get(0).modifier.getAmount());
    }

    @Test
    void planUtility_leftoverHigherOffsetKeyAtSameIndexIsSwept() {
        // The new array has ONE modifier (offset 0); a previous apply left a second at offset 1.
        Int2ObjectMap<StaticModifier[]> mods = new Int2ObjectOpenHashMap<>();
        mods.put(1, new StaticModifier[] { additive(5.0f) });
        Map<String, StaticModifier> existing = Map.of("1|ns:util:1", additive(3.0f));

        EquipStatBridge.UtilityPlan plan = planUtility(mods, existing);

        assertTrue(plan.removes.contains(new EquipStatBridge.StatKey(1, "ns:util:1")));
    }

    @Test
    void planUtility_statIndexNoLongerInMapHasAllItsUtilKeysSwept() {
        // The new map touches index 1 only; index 3 carries stale util keys 0 and 1.
        Int2ObjectMap<StaticModifier[]> mods = new Int2ObjectOpenHashMap<>();
        mods.put(1, new StaticModifier[] { additive(5.0f) });
        Map<String, StaticModifier> existing = new HashMap<>();
        existing.put("3|ns:util:0", additive(1.0f));
        existing.put("3|ns:util:1", additive(2.0f));

        EquipStatBridge.UtilityPlan plan = planUtility(mods, existing);

        assertTrue(plan.removes.contains(new EquipStatBridge.StatKey(3, "ns:util:0")));
        assertTrue(plan.removes.contains(new EquipStatBridge.StatKey(3, "ns:util:1")));
        // index 1 is present, so its keys are NOT swept
        assertFalse(plan.removes.contains(new EquipStatBridge.StatKey(1, "ns:util:0")));
    }

    @Test
    void planUtility_nullMapSweepsEveryStatIndex() {
        Map<String, StaticModifier> existing = new HashMap<>();
        existing.put("0|ns:util:0", additive(1.0f));
        existing.put("4|ns:util:0", additive(2.0f));

        EquipStatBridge.UtilityPlan plan = planUtility(null, existing);

        assertTrue(plan.puts.isEmpty());
        assertTrue(plan.removes.contains(new EquipStatBridge.StatKey(0, "ns:util:0")));
        assertTrue(plan.removes.contains(new EquipStatBridge.StatKey(4, "ns:util:0")));
    }
}
