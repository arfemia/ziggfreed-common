package com.ziggfreed.common.effect.outgoing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.effect.grid.RuntimeEffectGrid;

/**
 * Engine-free coverage for {@link DamageBonusReconciler}'s planning: the every-type and per-type split, which
 * is where a double count or a ceiling leak would hide (a hit of a type meets both effects), the id scheme,
 * and an unchanged pass planning nothing. The apply and remove engine calls and the per-tick pass need a
 * live server and are covered by the in-game smoke.
 */
class DamageBonusReconcilerTest {

    private static final int CEILING = 1000;

    private static Map<String, Double> types(Object... pairs) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return out;
    }

    @Test
    void ids_carryTheTypeAndTheWholePercent() {
        assertEquals("Zig_DamageBonus_Fire_10", DamageBonusReconciler.effectIdFor("Fire", 10));
        assertEquals("Zig_DamageBonus_Every_44", DamageBonusReconciler.effectIdFor("Every", 44));
        assertEquals("Zig_DamageBonus_Physical_Minus5", DamageBonusReconciler.effectIdFor("Physical", -5));
    }

    @Test
    void everyTypeLine_isOneBucket_andATypeCarriesOnlyItsOwnShare() {
        // +44% on every type, +5% Fire on top: one every-type bucket and one Fire bucket of 5, so a Fire hit
        // meets 44 + 5 = 49 and a Slashing hit meets 44, never 44 twice.
        RuntimeEffectGrid.Plan plan = DamageBonusReconciler.plan(Set.of(), 44.0,
                types("Physical", 0.0, "Fire", 5.0), CEILING);
        assertEquals(List.of(DamageBonusReconciler.effectIdFor("Every", 44), DamageBonusReconciler.effectIdFor("Fire", 5)),
                appliedIds(plan));
    }

    @Test
    void aTypesTotal_isHeldToTheCeiling_acrossBothBuckets() {
        // Every-type 900 plus Fire's own 300 = 1200, held to 1000: Fire's bucket carries the 100 left.
        List<RuntimeEffectGrid.Bucket> wanted = DamageBonusReconciler.desired(900.0, types("Fire", 300.0), CEILING);
        assertEquals(1000, wanted.stream().mapToInt(RuntimeEffectGrid.Bucket::value).sum());
        // An every-type reading past the ceiling is held on its own, and no type adds on top of it.
        assertEquals(List.of(new RuntimeEffectGrid.Bucket("Every", 1000)),
                DamageBonusReconciler.desired(1500.0, types("Fire", 10.0), CEILING));
    }

    @Test
    void aNegativeTotal_stopsAtAWholeHitOff() {
        assertEquals(List.of(new RuntimeEffectGrid.Bucket("Every", -100)),
                DamageBonusReconciler.desired(-250.0, types(), CEILING));
    }

    @Test
    void anUnchangedPass_plansNothing_andAZeroReadingSweeps() {
        Map<String, Double> byType = types("Ice", 7.0);
        Set<String> active = applyTo(new LinkedHashSet<>(), DamageBonusReconciler.plan(Set.of(), 20.0, byType, CEILING));
        assertTrue(DamageBonusReconciler.plan(active, 20.0, byType, CEILING).isEmpty(),
                "an idempotent pass must neither re-apply nor sweep");

        RuntimeEffectGrid.Plan cleared = DamageBonusReconciler.plan(active, 0.0, types("Ice", 0.0), CEILING);
        assertEquals(List.of(), appliedIds(cleared));
        assertEquals(2, cleared.remove().size(), "every bucket of this family comes off once its reading is gone");
    }

    private static List<String> appliedIds(RuntimeEffectGrid.Plan plan) {
        List<String> out = new ArrayList<>();
        for (RuntimeEffectGrid.Bucket bucket : plan.apply()) {
            out.add(DamageBonusReconciler.effectIdFor(bucket.channel(), bucket.value()));
        }
        return out;
    }

    private static Set<String> applyTo(Set<String> active, RuntimeEffectGrid.Plan plan) {
        active.removeAll(plan.remove());
        active.addAll(appliedIds(plan));
        return active;
    }
}
