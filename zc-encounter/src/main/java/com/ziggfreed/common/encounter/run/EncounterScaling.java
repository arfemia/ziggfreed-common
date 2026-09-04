package com.ziggfreed.common.encounter.run;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.health.HealthUtil;

/**
 * Party-size, run and power scaling of the subject's maximum health: one keyed multiplicative
 * modifier the library owns, applied once at the bind and reconciled after every phase (an in-place
 * role change rolls the new role's own maximum, so the modifier has to be put back). Keyed and
 * idempotent, so a companion's own health modifier composes beside it rather than colliding.
 */
public final class EncounterScaling {

    /** The stat-modifier key every encounter scale is written under. */
    public static final String MODIFIER_KEY = "zc_encounter_scale";

    private EncounterScaling() {
    }

    /**
     * The multiplier for a fight: {@code HealthMultiplier x runMultiplier x (1 + HealthPerMember x
     * (members - 1)) + HealthPerPowerPoint x power}, held between 1 and {@code MaxHealthMultiplier}.
     * A null {@code scale} reads as the group's defaults, which multiply by nothing.
     */
    public static double factor(@Nullable EncounterBindingAsset.Scale scale, int members, double power,
            double runMultiplier) {
        double perMember = scale == null ? EncounterBindingAsset.Scale.DEFAULT_HEALTH_PER_MEMBER : scale.healthPerMember();
        double flat = scale == null ? EncounterBindingAsset.Scale.DEFAULT_HEALTH_MULTIPLIER : scale.healthMultiplier();
        double perPower = scale == null ? EncounterBindingAsset.Scale.DEFAULT_HEALTH_PER_POWER_POINT
                : scale.healthPerPowerPoint();
        double ceiling = scale == null ? EncounterBindingAsset.Scale.DEFAULT_MAX_HEALTH_MULTIPLIER
                : scale.maxHealthMultiplier();
        double run = Double.isFinite(runMultiplier) && runMultiplier > 0.0 ? runMultiplier : 1.0;
        double extraMembers = Math.max(0, members - 1);
        double value = flat * run * (1.0 + perMember * extraMembers) + perPower * Math.max(0.0, power);
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(1.0, Math.min(Math.max(1.0, ceiling), value));
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
}
