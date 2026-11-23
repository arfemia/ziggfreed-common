package com.ziggfreed.common.npc.placement.runtime;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.registry.PlacementGates;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a standing NPC of a placement listing {@code Seasons} wears: the role of the entry in effect
 * ({@link NpcPlacementAsset#inEffect()}), put on in place through the engine's own role change
 * ({@code RoleChangeSystem.requestRoleChange}, the path the engine's {@code /npc role} takes): the same
 * entity, uuid, stamp and ledger row, the new role's model on it, and the change saved with the entity.
 *
 * <p>Two moments ask: every sweep that keeps a copy ({@code NpcPlacementReconciler}'s upkeep, which the
 * calendar's forced start and end sweeps reach), and every load of such a copy ({@link OnLoad}: a boot, a
 * chunk coming back), so a server that was down at a season's edge shows the right look as soon as the NPC is
 * in the world. A load whose copy no longer belongs (an earlier season's, at its old spot, or a season that
 * no longer runs) asks for a sweep instead, whose despawn pass takes it away and whose place pass stands the
 * right one. A placement without {@code Seasons} is never touched here: an edited role still changes only
 * what the next placement stands, as before.
 */
public final class PlacementLooks {

    /** Roles already reported as unknown, each once per process. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private PlacementLooks() {
    }

    /**
     * Put the role of {@code placement}'s entry in effect on the NPC {@code uuid}, when it wears another. World
     * thread, outside any system's processing window. Never throws.
     */
    static void wear(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid, @Nullable NpcPlacementAsset placement) {
        if (placement == null || !placement.hasSeasons()) {
            return;
        }
        try {
            Ref<EntityStore> ref = store.getExternalData().getRefFromUUID(uuid);
            if (ref != null && ref.isValid()) {
                wear(store, ref, placement);
            }
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not change the look of a placed NPC: " + t.getMessage());
        }
    }

    /**
     * Ask the engine to change {@code ref}'s role to the one {@code placement}'s entry in effect names, when it
     * wears another; true when a change was asked for. A role this server has not loaded is warned once and
     * the NPC keeps what it wears. World thread, outside any system's processing window.
     */
    static boolean wear(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull NpcPlacementAsset placement) {
        String wanted = NpcPlacementService.roleFor(placement.inEffect());
        if (wanted == null) {
            return false;
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        Role role = npc == null ? null : npc.getRole();
        if (role == null || role.isRoleChangeRequested()) {
            return false;
        }
        int index = NPCPlugin.get().getIndex(wanted);
        if (index < 0) {
            if (WARNED.add(wanted)) {
                SafeLog.warn("[placement] '" + placement.getId() + "' wants its NPC to wear the role '" + wanted
                        + "', which this server has not loaded, so it keeps the role it wears");
            }
            return false;
        }
        if (index == npc.getRoleIndex()) {
            return false;
        }
        String from = npc.getRoleName();
        RoleChangeSystem.requestRoleChange(ref, role, index, true, store);
        SafeLog.info("[placement] '" + placement.getId() + "' changes its NPC from role '" + from + "' to '"
                + wanted + "' for the season in effect");
        return true;
    }

    /**
     * Does the copy keyed {@code anchorKey} still belong to {@code placement} as it stands now, in
     * {@code world}: its gate allows it, and it stands under the tag of the entry in effect? World thread.
     */
    static boolean belongs(@Nonnull NpcPlacementAsset placement, @Nullable String anchorKey, @Nonnull World world,
            @Nonnull Store<EntityStore> store) {
        return placement.ownsAnchorKey(anchorKey)
                && !PlacementGates.decide(placement, world, store).isDenied()
                && NpcPlacementReconciler.matchesWorld(placement, world);
    }

    /**
     * The load half: a placed NPC of a {@code Seasons} placement coming into the world (a boot, a chunk loading,
     * the engine's own role change re-adding it). Runs inside the store's add, so it only reads the stamp and
     * defers the rest to the world's task queue. Never throws.
     */
    public static final class OnLoad extends RefSystem<EntityStore> {

        @Nonnull
        private final ComponentType<EntityStore, PlacedNpcComponent> placedType;
        @Nonnull
        private final Query<EntityStore> query;

        /** @param placedType the registered {@link PlacedNpcComponent} type */
        public OnLoad(@Nonnull ComponentType<EntityStore, PlacedNpcComponent> placedType) {
            this.placedType = placedType;
            this.query = Query.and(placedType, NPCEntity.getComponentType());
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            if (reason != AddReason.LOAD) {
                return; // A spawn already stands the role and the spot in effect.
            }
            try {
                PlacedNpcComponent placed = store.getComponent(ref, placedType);
                PlacedNpcIdentity identity = placed == null ? PlacedNpcIdentity.UNKNOWN : placed.toIdentity();
                if (identity.isUnknown()) {
                    return;
                }
                NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(identity.placementId());
                if (placement == null || !placement.hasSeasons()) {
                    return;
                }
                World world = store.getExternalData().getWorld();
                if (world == null) {
                    return;
                }
                String placementId = identity.placementId();
                String anchorKey = identity.anchorKey();
                world.execute(() -> settle(world, ref, placementId, anchorKey));
            } catch (Throwable t) {
                SafeLog.fine("[placement] a seasonal NPC's load check failed: " + t.getMessage());
            }
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        /** On the world thread: wear the season's role, or hand a copy that no longer belongs to a sweep. */
        private static void settle(@Nonnull World world, @Nonnull Ref<EntityStore> ref, @Nonnull String placementId,
                @Nullable String anchorKey) {
            try {
                if (!ref.isValid()) {
                    return;
                }
                NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(placementId);
                if (placement == null || !placement.hasSeasons()) {
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                if (!belongs(placement, anchorKey, world, store)) {
                    NpcPlacementReconciler.forceSweep(world, store);
                    return;
                }
                wear(store, ref, placement);
            } catch (Throwable t) {
                SafeLog.fine("[placement] could not settle a seasonal NPC after its load: " + t.getMessage());
            }
        }
    }
}
