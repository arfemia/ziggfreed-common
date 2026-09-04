package com.ziggfreed.common.encounter.seam;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The party's aggregated power for a fight, the number the binding row's
 * {@code Scale.HealthPerPowerPoint} multiplies. The library knows nothing about power; a companion
 * that tracks it for a region or a party fills this, and until one does a fight reads zero power,
 * which leaves every other term of the scale untouched.
 *
 * <p>Asked on the world thread, at each scale application and reconcile. Answer null for a fight
 * this fill cannot say anything about.
 */
@FunctionalInterface
public interface EncounterPowerSource {

    /** Answers nothing: the posture with no fill. */
    EncounterPowerSource NONE = (store, subjectRef, members) -> null;

    /**
     * The aggregated power for the fight around {@code subjectRef}, or null when unknown.
     *
     * @param members the live member refs at the moment of asking (may be empty)
     */
    @Nullable
    Double aggregatedPower(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> subjectRef,
                           @Nonnull List<Ref<EntityStore>> members);
}
