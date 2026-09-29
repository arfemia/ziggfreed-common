package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.ziggfreed.common.stats.EquipStatBridge.StatKey;
import com.ziggfreed.common.stats.EquipStatBridge.UtilityPut;
import com.ziggfreed.common.stats.gearset.GearSetKeys.TierRef;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * The diff between what the active tiers should have written and what is on the map, against a
 * fake lookup that also RECORDS every key it is asked about: the one thing that must never happen
 * is a gear-set sweep reaching a key another writer owns, so the record is what pins it.
 * {@link StaticModifier} is a plain constructible value here, as {@code StatMirrorTest} relies on.
 */
class GearSetPlanTest {

    private static final int STAT_MAP_SIZE = 3;

    /** A stat map stand-in: (index, key) to modifier, remembering every key it was asked about. */
    private static final class FakeStatMap {
        final Map<StatKey, StaticModifier> present = new HashMap<>();
        final Set<String> asked = new HashSet<>();

        void put(int index, String key, StaticModifier modifier) {
            present.put(new StatKey(index, key), modifier);
        }

        StaticModifier existing(Integer index, String key) {
            asked.add(key);
            return present.get(new StatKey(index, key));
        }
    }

    @Nonnull
    private static StaticModifier additive(float amount) {
        return new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, amount);
    }

    @Nonnull
    private static GearSetPlan.Desired desired(String setId, int tier, int statIndex, StaticModifier... modifiers) {
        Int2ObjectMap<StaticModifier[]> map = new Int2ObjectOpenHashMap<>();
        map.put(statIndex, modifiers);
        return new GearSetPlan.Desired(new TierRef(setId, tier), map);
    }

    @Test
    void aSweepNeverTouchesAKeyAnotherWriterOwns() {
        FakeStatMap map = new FakeStatMap();
        map.put(0, "ziggfreedcommon:util:0", additive(3f));
        map.put(0, "consumer:reward:0", additive(5f));
        map.put(0, "zigset:night_set:0:0", additive(4f));
        map.put(1, "ziggfreedcommon:armor:1", additive(2f));

        List<StatKey> strays = List.of(new StatKey(0, "ziggfreedcommon:util:0"), new StatKey(0, "consumer:reward:0"),
                new StatKey(0, "zigset:night_set:0:0"));
        GearSetPlan.Plan plan = GearSetPlan.plan(List.of(), List.of(new TierRef("night_set", 0)), strays,
                STAT_MAP_SIZE, map::existing);

        assertEquals(Set.of(new StatKey(0, "zigset:night_set:0:0")), plan.removes(),
                "only the engine's own key goes, whatever strays a careless caller lists");
        for (String key : map.asked) {
            assertTrue(key.startsWith(GearSetKeys.PREFIX), "asked about a foreign key: " + key);
        }
    }

    @Test
    void aTierThatWentInactiveIsSweptOnEveryIndexAndOffset() {
        FakeStatMap map = new FakeStatMap();
        map.put(0, "zigset:night_set:1:0", additive(6f));
        map.put(0, "zigset:night_set:1:1", additive(1f));
        map.put(2, "zigset:night_set:1:0", additive(2f));

        GearSetPlan.Plan plan = GearSetPlan.plan(List.of(), List.of(new TierRef("Night_Set", 1)), List.of(),
                STAT_MAP_SIZE, map::existing);

        assertTrue(plan.puts().isEmpty());
        assertEquals(Set.of(new StatKey(0, "zigset:night_set:1:0"), new StatKey(0, "zigset:night_set:1:1"),
                new StatKey(2, "zigset:night_set:1:0")), plan.removes(),
                "the tier ref lower-cases the set id, so the prefix matches what was written");
    }

    @Test
    void anEqualModifierIsDiffSkippedAndAChangedOneIsRewritten() {
        FakeStatMap map = new FakeStatMap();
        map.put(0, "zigset:night_set:0:0", additive(4f));
        map.put(1, "zigset:night_set:0:0", additive(9f));
        // One tier is ONE desired entry carrying every channel it writes; the engine builds it that way.
        Int2ObjectMap<StaticModifier[]> tier = new Int2ObjectOpenHashMap<>();
        tier.put(0, new StaticModifier[] {additive(4f)});
        tier.put(1, new StaticModifier[] {additive(7f)});

        GearSetPlan.Plan plan = GearSetPlan.plan(List.of(new GearSetPlan.Desired(new TierRef("night_set", 0), tier)),
                List.of(new TierRef("night_set", 0)), List.of(), STAT_MAP_SIZE, map::existing);

        assertEquals(1, plan.puts().size(), "index 0 already carries the same modifier");
        UtilityPut put = plan.puts().get(0);
        assertEquals(1, put.statIndex);
        assertEquals("zigset:night_set:0:0", put.key);
        assertEquals(7f, put.modifier.getAmount());
        assertTrue(plan.removes().isEmpty(), "a desired tier's keys are never swept");
    }

    @Test
    void aShorterArrayThanLastTimeSweepsTheLeftoverOffset() {
        FakeStatMap map = new FakeStatMap();
        map.put(0, "zigset:night_set:0:0", additive(4f));
        map.put(0, "zigset:night_set:0:1", additive(1f));

        GearSetPlan.Plan plan = GearSetPlan.plan(List.of(desired("night_set", 0, 0, additive(4f))),
                List.of(new TierRef("night_set", 0)), List.of(), STAT_MAP_SIZE, map::existing);

        assertTrue(plan.puts().isEmpty());
        assertEquals(Set.of(new StatKey(0, "zigset:night_set:0:1")), plan.removes());
    }

    @Test
    void aStrayADesiredTierWritesNowIsKeptAndTheRestGo() {
        FakeStatMap map = new FakeStatMap();
        map.put(0, "zigset:night_set:0:0", additive(4f));
        map.put(1, "zigset:old_set:2:0", additive(1f));

        List<StatKey> strays = List.of(new StatKey(0, "zigset:night_set:0:0"), new StatKey(1, "zigset:old_set:2:0"));
        GearSetPlan.Plan plan = GearSetPlan.plan(List.of(desired("night_set", 0, 0, additive(4f))), List.of(),
                strays, STAT_MAP_SIZE, map::existing);

        assertTrue(plan.puts().isEmpty(), "the stray the tier writes now is equal, so nothing is rewritten");
        assertEquals(Set.of(new StatKey(1, "zigset:old_set:2:0")), plan.removes(),
                "a key from a set that no longer exists is swept by the login read, with no prefix to remember it by");
    }

    @Test
    void resolveSkipsAndReportsAnUnknownChannelAndKeepsTheRest() {
        GearSetAsset.Tier tier = GearSetAsset.Tier.of(2, null, null, null, null, null, Map.of(
                "Health", new StatModifierSpec[] {StatModifierSpec.additive(4f), StatModifierSpec.of(0.1f, "Multiplicative", "Max")},
                "Not_A_Stat", new StatModifierSpec[] {StatModifierSpec.additive(1f)}));
        List<String> reported = new ArrayList<>();

        Int2ObjectMap<StaticModifier[]> resolved = GearSetPlan.resolve(tier,
                id -> "Health".equals(id) ? 5 : -1, reported::add);

        assertEquals(List.of("Not_A_Stat"), reported);
        assertEquals(1, resolved.size());
        assertEquals(2, resolved.get(5).length);
        assertEquals(StaticModifier.CalculationType.MULTIPLICATIVE, resolved.get(5)[1].getCalculationType());
        assertFalse(resolved.containsKey(-1));
    }

    @Test
    void tiersOfListsEachDesiredTierOnce() {
        List<GearSetPlan.Desired> desired = List.of(desired("night_set", 0, 0, additive(1f)),
                desired("night_set", 2, 1, additive(1f)));
        assertEquals(Set.of(new TierRef("night_set", 0), new TierRef("night_set", 2)), GearSetPlan.tiersOf(desired));
    }
}
