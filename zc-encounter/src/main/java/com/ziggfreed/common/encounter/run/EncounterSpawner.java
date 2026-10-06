package com.ziggfreed.common.encounter.run;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.builtin.encountermanager.EncounterManagerPlugin;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.HiddenFromAdventurePlayers;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.event.ResetReason;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections;

/**
 * Spawning and removing an encounter from Java, over the engine's own holder recipe: the manager
 * component naming the script, a transform, a nameplate, a uuid, the adventure-player hide, and
 * (on request) the creative marker model. The engine's own systems build the instruction tree and
 * start the script the moment the entity is added; nothing here runs the fight.
 *
 * <p>World thread only. A binding row switched off refuses the spawn, because the library would
 * neither bind, scale nor pay what it stood up.
 *
 * <p>The engine keeps an entity only in a chunk SECTION that is ticking: one added into a section that
 * is not is unloaded into that section's own entity list on the spot, and on Update 7 a section sleeps
 * whatever its column does (zc-world's {@code TickingSections}). {@link #spawn} wakes a section that is
 * in memory and refuses one that is not, so it never leaves an encounter parked; the console, or a call
 * placing a boss somewhere nobody is, goes through {@link #spawnWhenLoaded}, which asks the chunk store
 * for the section, ticking, first.
 */
public final class EncounterSpawner {

    private EncounterSpawner() {
    }

    /** Why a spawn was refused, for a caller that wants to say so. */
    public enum Refusal {
        UNKNOWN_ASSET,
        NOT_SPAWNABLE,
        DISABLED,
        ENGINE_FAILED
    }

    /** The answer to a spawn: the entity, or why there is none. */
    public record Outcome(@Nullable Ref<EntityStore> ref, @Nullable Refusal refusal) {

        public boolean spawned() {
            return ref != null;
        }
    }

    /**
     * Spawn {@code encounterAssetId} at {@code at}, stamping the run with {@code options}.
     *
     * @return the encounter entity, or null when the id names no spawnable encounter script, its
     *         binding is switched off, the chunk section under {@code at} cannot tick, or the engine refused
     *         the add (each reported)
     */
    @Nullable
    public static Ref<EntityStore> spawn(@Nonnull Store<EntityStore> store, @Nonnull String encounterAssetId,
            @Nonnull TransformComponent at, @Nonnull SpawnOptions options) {
        return trySpawn(store, encounterAssetId, at, options).ref();
    }

    /** {@link #spawn} answering the refusal reason as well. */
    @Nonnull
    public static Outcome trySpawn(@Nonnull Store<EntityStore> store, @Nonnull String encounterAssetId,
            @Nonnull TransformComponent at, @Nonnull SpawnOptions options) {
        BuilderInfo info = spawnableInfo(encounterAssetId);
        if (info == null) {
            boolean known = NPCPlugin.get() != null
                    && NPCPlugin.get().getIndex(encounterAssetId) != AssetMapWithIndexes.NOT_FOUND;
            SafeLog.warn(Encounters.LOG_PREFIX + " cannot spawn '" + encounterAssetId + "': "
                    + (known ? "not a spawnable encounter script" : "no encounter script by that id"));
            return new Outcome(null, known ? Refusal.NOT_SPAWNABLE : Refusal.UNKNOWN_ASSET);
        }
        String encounterId = info.getKeyName();
        EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
        if (row != null && !row.isEnabled()) {
            SafeLog.info(Encounters.LOG_PREFIX + " refusing to spawn '" + encounterId
                    + "': its binding is switched off");
            return new Outcome(null, Refusal.DISABLED);
        }
        try {
            World world = store.getExternalData().getWorld();
            double x = at.getPosition().x;
            double y = at.getPosition().y;
            double z = at.getPosition().z;
            if (!TickingSections.ensureTicking(world, x, y, z)) {
                SafeLog.warn(Encounters.LOG_PREFIX + " not spawning '" + encounterId + "' at " + Math.round(x)
                        + "," + Math.round(y) + "," + Math.round(z) + ": its chunk section is not loaded, and an"
                        + " entity added into a section that is not ticking is unloaded on the spot"
                        + " (spawnWhenLoaded brings the section up first)");
                return new Outcome(null, Refusal.ENGINE_FAILED);
            }
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(EncounterManager.getComponentType(), new EncounterManager(encounterId, info.getIndex()));
            holder.addComponent(TransformComponent.getComponentType(), at.clone());
            holder.addComponent(Nameplate.getComponentType(), new Nameplate(encounterId));
            holder.ensureComponent(UUIDComponent.getComponentType());
            holder.ensureComponent(HiddenFromAdventurePlayers.getComponentType());
            if (options.showMarker()) {
                Model model = EncounterManagerPlugin.get().getMarkerModel();
                holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
                holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
            }
            if (ZigEncounterRun.TYPE != null) {
                holder.addComponent(ZigEncounterRun.TYPE, ZigEncounterRun.forSpawn(options));
            }
            Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
            if (ref == null || !ref.isValid()) {
                SafeLog.warn(Encounters.LOG_PREFIX + " the engine refused to add '" + encounterId + "'");
                return new Outcome(null, Refusal.ENGINE_FAILED);
            }
            SafeLog.info(Encounters.LOG_PREFIX + " spawned '" + encounterId + "' at "
                    + Math.round(at.getPosition().x) + "," + Math.round(at.getPosition().y) + ","
                    + Math.round(at.getPosition().z)
                    + (options.ownerKey() == null ? "" : " for " + options.ownerKey()));
            return new Outcome(ref, null);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " spawning '" + encounterId + "' failed", t);
            return new Outcome(null, Refusal.ENGINE_FAILED);
        }
    }

    /**
     * {@link #trySpawn} once the chunk section under {@code at} is loaded and ticking. Always completes,
     * once, and never exceptionally: a section the store cannot bring up, or a store that is shutting
     * down, answers {@link Refusal#ENGINE_FAILED}. It completes on the world thread, except when the world
     * takes no more tasks (it is stopping): that {@code ENGINE_FAILED} answers on the thread that landed
     * the wake.
     */
    @Nonnull
    public static CompletableFuture<Outcome> spawnWhenLoaded(@Nonnull World world, @Nonnull String encounterAssetId,
            @Nonnull TransformComponent at, @Nonnull SpawnOptions options) {
        try {
            return whenWoken(TickingSections.wake(world, at.getPosition().x, at.getPosition().y, at.getPosition().z),
                    world, encounterAssetId, (sectionRef, error) ->
                            spawnInLoadedSection(world, encounterAssetId, at, options, sectionRef, error));
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not ask for the chunk section under '" + encounterAssetId
                    + "'", t);
            return CompletableFuture.completedFuture(new Outcome(null, Refusal.ENGINE_FAILED));
        }
    }

    /**
     * {@code spawn}'s answer once {@code woken} lands, run on {@code worldThread}. A world thread that takes
     * no more tasks (a stopping world's execute throws) never runs it, which fails only the dependent future:
     * that answers {@link Refusal#ENGINE_FAILED} instead, once. Package-private for the test.
     */
    @Nonnull
    static CompletableFuture<Outcome> whenWoken(@Nonnull CompletableFuture<Ref<ChunkStore>> woken,
            @Nonnull Executor worldThread, @Nonnull String encounterAssetId,
            @Nonnull BiFunction<Ref<ChunkStore>, Throwable, Outcome> spawn) {
        CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        woken.whenCompleteAsync((sectionRef, error) -> outcome.complete(spawn.apply(sectionRef, error)), worldThread)
                .whenComplete((ignored, rejected) -> {
                    if (rejected != null && outcome.complete(new Outcome(null, Refusal.ENGINE_FAILED))) {
                        SafeLog.warn(Encounters.LOG_PREFIX + " not spawning '" + encounterAssetId
                                + "': the world thread took no task for it: " + rejected);
                    }
                });
        return outcome;
    }

    @Nonnull
    private static Outcome spawnInLoadedSection(@Nonnull World world, @Nonnull String encounterAssetId,
            @Nonnull TransformComponent at, @Nonnull SpawnOptions options, @Nullable Ref<ChunkStore> sectionRef,
            @Nullable Throwable error) {
        if (error != null || sectionRef == null || !sectionRef.isValid()) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the chunk section under '" + encounterAssetId
                    + "' could not be brought up" + (error == null ? "" : ": " + error.getMessage()));
            return new Outcome(null, Refusal.ENGINE_FAILED);
        }
        try {
            return trySpawn(world.getEntityStore().getStore(), encounterAssetId, at, options);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " spawning '" + encounterAssetId + "' in its loaded section failed", t);
            return new Outcome(null, Refusal.ENGINE_FAILED);
        }
    }

    /**
     * Remove the encounter entity now. A run that had engaged and not concluded is settled as a
     * wipe first; the engine's own cleanup (a script authoring {@code CleanupOnRemove}) takes the
     * boss and its adds with it, and the removal ends the run with {@link ResetReason#REMOVED}.
     *
     * @param reason what to call the removal in the log
     * @return whether an encounter entity was removed
     */
    public static boolean despawn(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull String reason) {
        if (!encounterRef.isValid()) {
            return false;
        }
        String encounterId = EncounterRuns.encounterIdOn(store, encounterRef);
        if (encounterId == null) {
            return false;
        }
        ZigEncounterRun run = EncounterRuns.runOn(store, encounterRef);
        if (run != null && run.isEngaged() && !run.isConcluded()) {
            EncounterLifecycle.wipe(store, encounterRef, run, encounterId, false);
        }
        SafeLog.info(Encounters.LOG_PREFIX + " removing '" + encounterId + "': " + reason);
        try {
            store.removeEntity(encounterRef, RemoveReason.REMOVE);
            return true;
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " removing '" + encounterId + "' failed", t);
            return false;
        }
    }

    /** The builder info for a SPAWNABLE encounter script under {@code assetId}, or null. */
    @Nullable
    public static BuilderInfo spawnableInfo(@Nonnull String assetId) {
        BuilderInfo info = encounterInfo(assetId);
        return info != null && info.getBuilder().isSpawnable() ? info : null;
    }

    /** The builder info for any encounter script (spawnable or abstract) under {@code assetId}, or null. */
    @Nullable
    public static BuilderInfo encounterInfo(@Nonnull String assetId) {
        NPCPlugin npc = NPCPlugin.get();
        if (npc == null || assetId.isBlank()) {
            return null;
        }
        int index = npc.getIndex(assetId);
        if (index == AssetMapWithIndexes.NOT_FOUND) {
            return null;
        }
        BuilderInfo info = npc.getBuilderManager().tryGetBuilderInfo(index);
        if (info == null || info.getBuilder().category() != EncounterManager.class) {
            return null;
        }
        return info;
    }
}
