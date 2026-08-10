package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.common.map.IWeightedMap;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.modules.entity.component.FromPrefabInstance;
import com.hypixel.hytale.server.core.modules.entity.component.FromWorldGen;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.assets.spawnmarker.config.SpawnMarker;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import com.ziggfreed.common.util.SafeLog;

/**
 * Notices worldgen spawn markers as their chunks load, so a structure anchor has something to
 * resolve against.
 *
 * <p>A structure anchor cannot be evaluated on demand: nothing knows where a generated village is
 * until its chunk loads and the engine adds the marker entity. So this system records every
 * sighting into {@link StructureAnchorIndex} (what the anchors read) and
 * {@link StructureMarkerSightings} (what an author reads to discover real marker ids), then asks
 * for a sweep so an NPC anchored to that structure appears at once rather than at the next
 * unrelated trigger.
 *
 * <p>It deliberately does NOT override {@code getGroup()}: the engine's own marker group is
 * internal, so this runs in the default group alongside it.
 *
 * <p><b>Engine-window discipline.</b> {@code onEntityAdd} runs inside the ECS add window, so this
 * only READS components off the {@link Holder} and never spawns anything itself - the sweep it
 * asks for is deferred onto the world task queue by the reconciler. The whole body is guarded so a
 * throw can never break chunk loading.
 *
 * <p>A marker with no {@link FromPrefabInstance} is ignored: without a stable instance id its
 * anchor key would change between restarts, and a changed key mints a duplicate NPC beside the one
 * already standing.
 */
public final class PlacementMarkerSystem extends HolderSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query =
            Query.and(SpawnMarkerEntity.getComponentType(), FromWorldGen.getComponentType());

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store) {
        try {
            SpawnMarkerEntity marker = holder.getComponent(SpawnMarkerEntity.getComponentType());
            if (marker == null) {
                return;
            }
            String markerId = marker.getSpawnMarkerId();
            if (markerId == null || markerId.isEmpty()) {
                return;
            }
            FromPrefabInstance fromPrefab = holder.getComponent(FromPrefabInstance.getComponentType());
            if (fromPrefab == null) {
                return;
            }
            TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
            if (transform == null || transform.getPosition() == null) {
                return;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }

            double x = transform.getPosition().x();
            double y = transform.getPosition().y();
            double z = transform.getPosition().z();
            List<String> roles = resolveRoleNames(markerId);

            StructureMarkerSightings.getInstance()
                    .record(world.getName(), markerId, x, y, z, fromPrefab.getPrefabInstanceId(), roles);

            boolean isNew = StructureAnchorIndex.record(world, markerId, roles,
                    fromPrefab.getPrefabInstanceId(), x, y, z);
            if (isNew) {
                // A new anchor exists that did not before, so the last sweep's answer is stale.
                NpcPlacementReconciler.clearDebounce(world);
                NpcPlacementReconciler.requestSweep(world, store);
            }
        } catch (Throwable t) {
            SafeLog.fine("[placement] marker sighting failed: " + t.getMessage());
        }
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store) {
        // Markers come and go with their chunks. The index is a record of what EXISTS in the world,
        // not of what is currently resident, so a chunk unload must not forget one.
    }

    /** The non-blank NPC role names this marker asset can spawn, or an empty list. */
    @Nonnull
    private static List<String> resolveRoleNames(@Nonnull String markerId) {
        List<String> roles = new ArrayList<>();
        try {
            SpawnMarker asset = SpawnMarker.getAssetMap().getAsset(markerId);
            if (asset == null) {
                return roles;
            }
            IWeightedMap<SpawnMarker.SpawnConfiguration> configs = asset.getWeightedConfigurations();
            if (configs == null) {
                return roles;
            }
            configs.forEach(config -> {
                if (config == null) {
                    return;
                }
                String npc = config.getNpc();
                if (npc != null && !npc.isBlank() && !roles.contains(npc)) {
                    roles.add(npc);
                }
            });
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not resolve roles for marker '" + markerId + "': " + t.getMessage());
        }
        return roles;
    }
}
