package com.ziggfreed.common.encounter.types;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.run.EncounterSubjects;

/**
 * The one way an action aimed at the fight's boss finds it ({@code ZigStunTarget}, {@code ZigControlRule}): the entity
 * in the named target slot when the script names one (none when that slot is empty), else the triggering sensor's
 * target, else the binding row's subject.
 */
final class ActionTargets {

    private ActionTargets() {
    }

    @Nullable
    static Ref<EntityStore> resolve(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable String targetSlot, @Nonnull ExecutionSupport executionSupport, @Nullable InfoProvider sensorInfo,
            @Nullable EncounterBindingAsset row) {
        if (targetSlot != null) {
            MarkedEntitySupport slots = executionSupport.getMarkedEntitySupport();
            Ref<EntityStore> named = slots == null ? null : slots.getMarkedEntityRef(targetSlot);
            return named != null && named.isValid() ? named : null;
        }
        IPositionProvider position = sensorInfo == null ? null : sensorInfo.getPositionProvider();
        Ref<EntityStore> sensed = position == null ? null : position.getTarget();
        if (sensed != null && sensed.isValid()) {
            return sensed;
        }
        return EncounterSubjects.resolve(store, ref, row == null ? null : row.getSubject(), row != null);
    }
}
