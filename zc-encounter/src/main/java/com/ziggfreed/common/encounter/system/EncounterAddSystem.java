package com.ziggfreed.common.encounter.system;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.spawning.SpawnLineage;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.util.SafeLog;

/**
 * Notices an ADD the moment it rises. The engine stamps every encounter entity with a native
 * {@link SpawnLineage} and copies it onto everything the encounter's spawners raise (and onto
 * whatever those spawn in turn), so a lineage landing on an NPC names the encounter, and through the
 * lineage index the run, the NPC belongs to.
 *
 * <p>This system only RECORDS the NPC: the callback runs inside the store's processing lock, where
 * nothing may be written, and before the engine has built the NPC's stats. The encounter's own tick
 * scales it ({@code EncounterTickSystem}). An NPC loaded from a chunk already carries its lineage, so
 * it raises no notice here and a reload never scales an add twice.
 */
public final class EncounterAddSystem extends RefChangeSystem<EntityStore, SpawnLineage> {

    @Nonnull
    @Override
    public ComponentType<EntityStore, SpawnLineage> componentType() {
        return SpawnLineage.getComponentType();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull SpawnLineage lineage,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            UUID runId = EncounterRuns.runOfLineage(lineage.getLineageId());
            if (runId != null) {
                EncounterRuns.notePendingAdd(runId, ref);
            }
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " noting an add failed", t);
        }
    }

    @Override
    public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable SpawnLineage oldComponent,
            @Nonnull SpawnLineage newComponent, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
    }

    @Override
    public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull SpawnLineage component,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
    }
}
