package com.ziggfreed.common.encounter.system;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.event.ResetReason;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.util.SafeLog;

/**
 * Where a run BEGINS and ENDS with its entity: every encounter entity added to a store carries a
 * run from its first tick (whoever placed it: the spawner, a builder's command, a prefab, a chunk
 * load), and every removal ends the run with the reason the engine gave.
 *
 * <p>A builder hot-reload removes and re-adds the entity under {@code UNLOAD} and {@code LOAD},
 * restarting the script at its start state; the reload listener notes the reloaded builder so the
 * removal reads as {@link ResetReason#RELOADED} rather than a world unload.
 */
public final class EncounterLifecycleSystems {

    /** How long after a builder reload notice an unload of that builder's encounters reads as the reload. */
    private static final long RELOAD_WINDOW_NANOS = 15_000_000_000L;

    private static final Map<Integer, Long> RELOADING = new ConcurrentHashMap<>();

    private EncounterLifecycleSystems() {
    }

    /** Note that {@code info}'s builder is being reloaded; the engine's own listener re-adds its encounters. */
    public static void noteReload(@Nonnull BuilderInfo info) {
        if (info.getBuilder().category() != EncounterManager.class) {
            return;
        }
        RELOADING.put(info.getIndex(), System.nanoTime());
    }

    private static boolean isReloading(int builderIndex) {
        Long at = RELOADING.get(builderIndex);
        return at != null && System.nanoTime() - at < RELOAD_WINDOW_NANOS;
    }

    /** Attaches a run to every encounter entity that arrives without one, before its first tick. */
    public static final class Attach extends HolderSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return EncounterManager.getComponentType();
        }

        @Override
        public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store) {
            if (ZigEncounterRun.TYPE == null) {
                return;
            }
            try {
                ZigEncounterRun run = holder.getComponent(ZigEncounterRun.TYPE);
                if (run == null) {
                    holder.addComponent(ZigEncounterRun.TYPE, new ZigEncounterRun());
                } else if (run.isEnded()) {
                    holder.putComponent(ZigEncounterRun.TYPE, (ZigEncounterRun) run.clone());
                }
            } catch (Throwable t) {
                SafeLog.warn(Encounters.LOG_PREFIX + " could not attach a run to an encounter entity", t);
            }
        }

        @Override
        public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store) {
        }
    }

    /** Ends the run when its entity leaves the store, for whatever reason the engine gives. */
    public static final class Remove extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return EncounterManager.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                ZigEncounterRun run = EncounterRuns.runOn(store, ref);
                EncounterManager manager = store.getComponent(ref, EncounterManager.getComponentType());
                if (run == null || manager == null || manager.getEncounterId() == null) {
                    return;
                }
                ResetReason why = switch (reason) {
                    case REMOVE, BUILDER_TOOLS_UNDO -> ResetReason.REMOVED;
                    default -> isReloading(manager.getEncounterIndex()) ? ResetReason.RELOADED : ResetReason.WORLD_UNLOAD;
                };
                EncounterLifecycle.reset(store, ref, run, manager.getEncounterId(), why, false);
            } catch (Throwable t) {
                SafeLog.warn(Encounters.LOG_PREFIX + " ending a run on removal failed", t);
            }
        }
    }
}
