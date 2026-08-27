package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.hypixel.hytale.common.map.IWeightedMap;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.modules.entity.component.FromPrefabInstance;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.assets.spawnmarker.config.SpawnMarker;
import com.hypixel.hytale.server.spawning.blockstates.SpawnMarkerBlockReference;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import com.ziggfreed.common.util.SafeLog;

/**
 * Notices spawn markers as their chunks load, so a structure anchor has something to resolve
 * against.
 *
 * <p>A structure anchor cannot be evaluated on demand: nothing knows where a structure's markers
 * are until their chunks load and the engine adds the marker entities. So this system records
 * every sighting into {@link StructureAnchorIndex} (what the anchors read) and
 * {@link StructureMarkerSightings} (what an author reads to discover real marker ids), then asks
 * for a sweep so an NPC anchored to that structure appears at once rather than at the next
 * unrelated trigger.
 *
 * <p><b>Identity is the marker's own spot on the block grid, and the query is
 * {@code SpawnMarkerEntity} ALONE.</b> A marker entity reaches a store two ways, and only its
 * position is stable across both. The engine synthesizes one fresh (new uuid, WITH a
 * {@link SpawnMarkerBlockReference}) from a {@code SpawnMarkerBlock} as an open-world chunk
 * loads; an instance world built from saved chunks loads its marker entities directly, carrying
 * NO block reference and NO worldgen/prefab provenance component at all (live-scanned in the
 * Forgotten Temple: every marker reads {@code blockRef=NO fromPrefabInstance=NO}). Requiring ANY
 * second component therefore silently excludes one of the two paths. A structure never moves
 * relative to its own layout and a block-synthesized marker stands centered on its block, so the
 * FLOORED transform position is one key that is stable across chunk reloads, restarts and
 * instance re-creations, and identical for both paths.
 *
 * <p>It deliberately does NOT override {@code getGroup()}: the engine's own marker group is
 * internal, so this runs in the default group alongside it.
 *
 * <p><b>Engine-window discipline.</b> {@code onEntityAdd} runs inside the ECS add window, so this
 * only READS components off the {@link Holder} and never spawns anything itself - the sweep it
 * asks for is deferred onto the world task queue by the reconciler. The whole body is guarded so a
 * throw can never break chunk loading.
 */
public final class PlacementMarkerSystem extends HolderSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = SpawnMarkerEntity.getComponentType();

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
            // Every early return below is a marker this system SAW and could not record, so each
            // says why - a silent return here reads as "the sighting never fires" from outside.
            String markerId = marker.getSpawnMarkerId();
            if (markerId == null || markerId.isEmpty()) {
                SafeLog.warn("[placement] marker entity added with no marker id - cannot record a sighting");
                return;
            }
            TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
            if (transform == null || transform.getPosition() == null) {
                SafeLog.warn("[placement] marker '" + markerId + "' added with no transform - cannot record a sighting");
                return;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                SafeLog.warn("[placement] marker '" + markerId + "' added on a store with no world - cannot record a sighting");
                return;
            }

            double x = transform.getPosition().x();
            double y = transform.getPosition().y();
            double z = transform.getPosition().z();
            String instanceId = (int) Math.floor(x) + "_" + (int) Math.floor(y) + "_" + (int) Math.floor(z);
            List<String> roles = resolveRoleNames(markerId);

            StructureMarkerSightings.getInstance()
                    .record(world.getName(), markerId, x, y, z, instanceId, roles);

            boolean isNew = StructureAnchorIndex.record(world, markerId, roles, instanceId, x, y, z);
            if (isNew) {
                // fine, not info: an open world walks past dozens of markers, so this is a raised-
                // log-level trail only - /mmonpc list structures|markers is the interactive surface
                // for discovering what a world emits.
                SafeLog.fine("[placement] marker '" + markerId + "' sighted in '" + world.getName()
                        + "' at (" + Math.round(x) + "," + Math.round(y) + "," + Math.round(z)
                        + ") block=" + instanceId + " roles=" + roles);
                // A new anchor exists that did not before, so the last sweep's answer is stale.
                NpcPlacementReconciler.clearDebounce(world);
                NpcPlacementReconciler.requestSweep(world, store);
            }
        } catch (Throwable t) {
            // WARN, not fine: a throw here silently costs a structure anchor its sighting, which
            // reads as "the NPC just never appeared" with nothing in the log to say why.
            SafeLog.warn("[placement] marker sighting failed: " + t);
        }
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store) {
        // Markers come and go with their chunks. The index is a record of what EXISTS in the world,
        // not of what is currently resident, so a chunk unload must not forget one.
    }

    /**
     * Live diagnostic: one line per spawn-marker entity currently RESIDENT in {@code world}'s
     * store - the marker id, whether it carries the block reference this system keys sightings by,
     * whether it carries a prefab-instance stamp, and where it stands. The ground truth behind
     * "why does a structure anchor never resolve here": an empty answer with the structure's NPCs
     * visibly standing means the markers live only in unloaded chunks or not at all, and a row
     * showing {@code blockRef=NO} names the component gap directly. WORLD-THREAD ONLY; never
     * throws (a failure comes back as its own line).
     */
    @Nonnull
    public static List<String> scanLive(@Nonnull World world, @Nonnull Store<EntityStore> store) {
        List<String> out = new ArrayList<>();
        ComponentType<EntityStore, SpawnMarkerEntity> markerType;
        try {
            markerType = SpawnMarkerEntity.getComponentType();
        } catch (Throwable t) {
            out.add("spawn-marker component type unresolvable: " + t);
            return out;
        }
        if (markerType == null) {
            out.add("spawn-marker component type is null (spawning plugin not initialized)");
            return out;
        }
        ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        try {
            store.forEachEntityParallel(markerType, (index, chunk, cmdBuffer) -> {
                try {
                    SpawnMarkerEntity marker = chunk.getComponent(index, markerType);
                    if (marker == null) {
                        return;
                    }
                    String id = marker.getSpawnMarkerId();
                    SpawnMarkerBlockReference blockRef =
                            chunk.getComponent(index, SpawnMarkerBlockReference.getComponentType());
                    FromPrefabInstance fromPrefab =
                            chunk.getComponent(index, FromPrefabInstance.getComponentType());
                    TransformComponent transform =
                            chunk.getComponent(index, TransformComponent.getComponentType());
                    StringBuilder sb = new StringBuilder();
                    sb.append(id == null || id.isEmpty() ? "<no marker id>" : id);
                    if (transform != null && transform.getPosition() != null) {
                        sb.append(" @ (").append(Math.round(transform.getPosition().x())).append(',')
                                .append(Math.round(transform.getPosition().y())).append(',')
                                .append(Math.round(transform.getPosition().z())).append(')');
                    } else {
                        sb.append(" @ <no transform>");
                    }
                    Vector3i blockPos = blockRef == null ? null : blockRef.getBlockPosition();
                    sb.append(blockRef == null ? " blockRef=NO"
                            : blockPos == null ? " blockRef=YES(no position)"
                                    : " blockRef=(" + blockPos.x() + ',' + blockPos.y() + ',' + blockPos.z() + ')');
                    sb.append(fromPrefab == null ? " fromPrefabInstance=NO"
                            : " fromPrefabInstance=" + fromPrefab.getPrefabInstanceId());
                    lines.add(sb.toString());
                } catch (Throwable t) {
                    lines.add("scan entry failed: " + t);
                }
            });
        } catch (Throwable t) {
            out.add("scan failed: " + t);
        }
        out.addAll(lines);
        return out;
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
