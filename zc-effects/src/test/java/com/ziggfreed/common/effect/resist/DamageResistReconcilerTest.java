package com.ziggfreed.common.effect.resist;

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
import com.ziggfreed.common.stats.damage.DamageResistances;

/**
 * Engine-free coverage for {@link DamageResistReconciler}: the whole-percent bucket math, the generated
 * effect-id scheme, and the reconcile PLAN (apply / swap / sweep / idempotency) driven against a fake
 * active-effect set standing in for a live {@code EffectControllerComponent}.
 * The apply and remove engine calls, the per-tick pass and the consumer rules need a live server and are
 * covered by the in-game smoke.
 */
class DamageResistReconcilerTest {

    private static final int CEILING = 80;

    private static Map<String, Double> points(Object... pairs) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return out;
    }

    // ==================== bucket math ====================

    @Test
    void percentFor_roundsToWholePercent() {
        assertEquals(4, DamageResistReconciler.percentFor(4.4, CEILING));
        assertEquals(5, DamageResistReconciler.percentFor(4.6, CEILING));
        assertEquals(12, DamageResistReconciler.percentFor(12.0, CEILING));
    }

    @Test
    void percentFor_flooringAndCap() {
        assertEquals(0, DamageResistReconciler.percentFor(0.0, CEILING));
        assertEquals(0, DamageResistReconciler.percentFor(0.4, CEILING), "a sub-half-point reading is no bucket");
        assertEquals(0, DamageResistReconciler.percentFor(-25.0, CEILING), "a negative total never amplifies damage");
        assertEquals(CEILING, DamageResistReconciler.percentFor(90.0, CEILING));
        assertEquals(CEILING, DamageResistReconciler.percentFor(250.0, CEILING));
    }

    @Test
    void percentFor_nonFiniteReadingIsZero() {
        assertEquals(0, DamageResistReconciler.percentFor(Double.NaN, CEILING));
        assertEquals(0, DamageResistReconciler.percentFor(Double.POSITIVE_INFINITY, CEILING));
    }

    // ==================== id scheme ====================

    @Test
    void effectId_isTypeAndPercentUnderItsOwnPrefix() {
        assertEquals("Zig_DamageResist_Fire_12", DamageResistReconciler.effectIdFor("Fire", 12));
        assertEquals("Zig_DamageResist_Projectile_80", DamageResistReconciler.effectIdFor("Projectile", 80));
    }

    // ==================== plan ====================

    @Test
    void plan_appliesABucketForEveryNonZeroType() {
        RuntimeEffectGrid.Plan plan = DamageResistReconciler.plan(Set.of(), points("Fire", 12.0, "Void", 7.0), CEILING);

        assertEquals(List.of(
                        DamageResistReconciler.effectIdFor("Fire", 12),
                        DamageResistReconciler.effectIdFor("Void", 7)),
                appliedIds(plan));
        assertEquals(List.of(), plan.remove());
    }

    @Test
    void plan_zeroTypeGetsNoBucket() {
        assertTrue(DamageResistReconciler.plan(Set.of(), points("Fire", 0.0), CEILING).isEmpty(),
                "nothing to apply and nothing to sweep");
    }

    @Test
    void plan_repeatPassWithUnchangedStatsDoesNothing() {
        Map<String, Double> stats = points("Fire", 12.0, "Arcane", 3.0);
        Set<String> active = applyTo(new LinkedHashSet<>(), DamageResistReconciler.plan(Set.of(), stats, CEILING));

        assertTrue(DamageResistReconciler.plan(active, stats, CEILING).isEmpty(),
                "an idempotent pass must neither re-apply nor sweep");
    }

    @Test
    void plan_changedValueSwapsTheBucket() {
        Set<String> active = Set.of(DamageResistReconciler.effectIdFor("Fire", 12));

        RuntimeEffectGrid.Plan plan = DamageResistReconciler.plan(active, points("Fire", 20.0), CEILING);

        assertEquals(List.of(DamageResistReconciler.effectIdFor("Fire", 20)), appliedIds(plan));
        assertEquals(List.of(DamageResistReconciler.effectIdFor("Fire", 12)), plan.remove());
    }

    @Test
    void plan_statFallingToZeroRemovesTheBucket() {
        Set<String> active = Set.of(DamageResistReconciler.effectIdFor("Water", 8));

        RuntimeEffectGrid.Plan plan = DamageResistReconciler.plan(active, points("Water", 0.0), CEILING);

        assertEquals(List.of(), appliedIds(plan));
        assertEquals(List.of(DamageResistReconciler.effectIdFor("Water", 8)), plan.remove());
    }

    @Test
    void plan_overCeilingReadingCollapsesOntoTheCeilingBucket() {
        RuntimeEffectGrid.Plan plan = DamageResistReconciler.plan(
                Set.of(DamageResistReconciler.effectIdFor("Lightning", CEILING)), points("Lightning", 400.0), CEILING);

        assertTrue(plan.isEmpty(), "a reading past the ceiling is already served by the ceiling bucket");
    }

    @Test
    void plan_leavesForeignEffectsAlone() {
        Set<String> active = new LinkedHashSet<>(List.of(
                "Other_Mod_Resist_Ice_5",
                "Vanilla_Regeneration",
                DamageResistReconciler.effectIdFor("Ice", 5)));

        assertTrue(DamageResistReconciler.plan(active, points("Ice", 5.0), CEILING).isEmpty(),
                "only this family's own ids are ever swept or re-applied");
    }

    @Test
    void plan_sweepsAStaleBucketLeftByAnEarlierRun() {
        Set<String> active = Set.of(DamageResistReconciler.effectIdFor("Poison", 33));

        RuntimeEffectGrid.Plan plan = DamageResistReconciler.plan(active, points(), CEILING);

        assertEquals(List.of(DamageResistReconciler.effectIdFor("Poison", 33)), plan.remove());
    }

    /**
     * Applying a plan and re-planning against the resulting state settles in ONE pass for every type at
     * once, which is what lets the per-tick pass cost nothing while nothing changes.
     */
    @Test
    void plan_convergesInOnePassAcrossTheWholeRoster() {
        Map<String, Double> stats = new LinkedHashMap<>();
        double value = 3.0;
        for (String type : DamageResistances.TYPE_IDS) {
            stats.put(type, value);
            value += 6.5;
        }
        Set<String> active = applyTo(new LinkedHashSet<>(), DamageResistReconciler.plan(Set.of(), stats, CEILING));

        assertEquals(DamageResistances.TYPE_IDS.size(), active.size(), "one effect per type");
        assertTrue(DamageResistReconciler.plan(active, stats, CEILING).isEmpty());
    }

    // ==================== helpers ====================

    /** The fake sink: fold a plan into an active-effect id set the way the controller would. */
    private static Set<String> applyTo(Set<String> active, RuntimeEffectGrid.Plan plan) {
        active.removeAll(plan.remove());
        active.addAll(appliedIds(plan));
        return active;
    }

    private static List<String> appliedIds(RuntimeEffectGrid.Plan plan) {
        List<String> out = new ArrayList<>();
        for (RuntimeEffectGrid.Bucket bucket : plan.apply()) {
            out.add(DamageResistReconciler.effectIdFor(bucket.channel(), bucket.value()));
        }
        return out;
    }
}
