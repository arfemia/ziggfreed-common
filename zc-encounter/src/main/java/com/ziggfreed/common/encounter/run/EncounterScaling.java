package com.ziggfreed.common.encounter.run;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.health.HealthUtil;

/**
 * Party-size, run and power scaling of maximum health, for the subject and for the adds the fight's
 * spawners raise: one keyed multiplicative modifier each, the library's own. The subject's is
 * applied once at the bind and reconciled after every phase (an in-place role change rolls the new
 * role's own maximum, so the modifier has to be put back); an add's is applied once, the tick after
 * it rises, under a key of its own. Keyed and idempotent, so a companion's own health modifier
 * composes beside them rather than colliding.
 */
public final class EncounterScaling {

    /** The stat-modifier key every encounter scale is written under. */
    public static final String MODIFIER_KEY = "zc_encounter_scale";

    /** The stat-modifier key every add's scale is written under, apart from the subject's. */
    public static final String ADD_MODIFIER_KEY = "zc_encounter_add_scale";

    private EncounterScaling() {
    }

    /**
     * The multiplier for a fight: {@code HealthMultiplier x runMultiplier x (1 + HealthPerMember x
     * (members - 1)) + HealthPerPowerPoint x power}, held between 1 and {@code MaxHealthMultiplier}.
     * A null {@code scale} reads as the group's defaults, which multiply by nothing.
     */
    public static double factor(@Nullable EncounterBindingAsset.Scale scale, int members, double power,
            double runMultiplier) {
        if (scale == null) {
            return grow(EncounterBindingAsset.Scale.DEFAULT_HEALTH_PER_MEMBER,
                    EncounterBindingAsset.Scale.DEFAULT_HEALTH_MULTIPLIER,
                    EncounterBindingAsset.Scale.DEFAULT_HEALTH_PER_POWER_POINT,
                    EncounterBindingAsset.Scale.DEFAULT_MAX_HEALTH_MULTIPLIER, members, power, runMultiplier);
        }
        return grow(scale.healthPerMember(), scale.healthMultiplier(), scale.healthPerPowerPoint(),
                scale.maxHealthMultiplier(), members, power, runMultiplier);
    }

    /**
     * The multiplier for one add: {@code HealthMultiplier x (1 + HealthPerMember x (members - 1)) +
     * HealthPerPowerPoint x power}, held between 1 and the group's {@code MaxHealthMultiplier}. The
     * spawn call's run multiplier is the subject's and never reaches an add. A null {@code adds} (the
     * row authors no add scale) scales no add: 1.
     */
    public static double addFactor(@Nullable EncounterBindingAsset.AddScale adds, int members, double power) {
        if (adds == null) {
            return 1.0;
        }
        return grow(adds.healthPerMember(), adds.healthMultiplier(), adds.healthPerPowerPoint(),
                adds.maxHealthMultiplier(), members, power, 1.0);
    }

    /** The member count a scale reads: the live roster, or the party the spawn call seeded when that is larger. */
    public static int memberCount(@Nonnull List<?> memberRefs, @Nonnull ZigEncounterRun run) {
        return Math.max(memberRefs.size(), run.seedMembers().size());
    }

    /**
     * The party's aggregated power for a scale whose {@code perPower} reads it, else 0 without asking
     * the power seam at all, so an unfilled seam reports itself only where power counts.
     */
    public static double powerFor(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> subjectRef,
            @Nonnull List<Ref<EntityStore>> members, double perPower) {
        return perPower != 0.0 ? EncounterSeams.aggregatedPower(store, subjectRef, members) : 0.0;
    }

    /**
     * Put {@code factor} on {@code subjectRef}: the first application heals to the new maximum, a
     * later one only reconciles the modifier (a replace or a shrink clamps, never heals). A factor
     * of exactly 1 strips the modifier. Answers whether anything changed.
     */
    public static boolean apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> subjectRef,
            double factor, boolean firstApplication) {
        if (!subjectRef.isValid()) {
            return false;
        }
        if (firstApplication) {
            return HealthUtil.scaleMaxHealth(store, subjectRef, factor, MODIFIER_KEY);
        }
        return HealthUtil.reconcileMaxHealth(store, subjectRef, factor, MODIFIER_KEY);
    }

    /**
     * Put {@code factor} on an add, once, healing it to its new maximum. A factor of 1, an add already
     * scaled, or one whose health is not built yet changes nothing. Answers whether it scaled.
     */
    public static boolean applyToAdd(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> addRef,
            double factor) {
        return addRef.isValid() && HealthUtil.scaleMaxHealth(store, addRef, factor, ADD_MODIFIER_KEY);
    }

    private static double grow(double perMember, double flat, double perPower, double ceiling, int members,
            double power, double runMultiplier) {
        double run = Double.isFinite(runMultiplier) && runMultiplier > 0.0 ? runMultiplier : 1.0;
        double extraMembers = Math.max(0, members - 1);
        double value = flat * run * (1.0 + perMember * extraMembers) + perPower * Math.max(0.0, power);
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(1.0, Math.min(Math.max(1.0, ceiling), value));
    }
}
