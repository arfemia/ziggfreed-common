package com.ziggfreed.common.npc.placement;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.npc.NpcSpawnService;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.WorldIdentity;

/**
 * The thin policy layer over {@link NpcSpawnService} that actually puts a placement's NPC in the
 * world, takes it out again, and applies the {@code Lifecycle} knobs.
 *
 * <p>It deliberately owns no decisions: WHETHER to place is {@link PlacementGates} plus
 * {@link NpcPlacementReconciler}, WHERE is {@link PlacementAnchors}, and WHAT is already placed is
 * {@link NpcPlacementLedger}. This class is the one place those decisions become engine calls, so
 * every path that creates a placed NPC stamps the same component and writes the same ledger row.
 *
 * <p><b>World-thread only.</b> {@code spawnEntity}/{@code removeEntity} are valid only outside the
 * ECS processing window, which is exactly what a {@code world.execute} task provides. Every method
 * is guarded so a failure degrades to "not placed" rather than breaking a sweep or a chunk load.
 */
public final class NpcPlacementService {

    /** Modifier key for the fortify bonus (namespaced, so nothing else clobbers it). */
    private static final String FORTIFY_MODIFIER = "ziggfreedcommon:placement_health";

    private NpcPlacementService() {
    }

    // ==================== place ====================

    /**
     * Place {@code placement}'s NPC at {@code position} in {@code world}, stamping it with a
     * {@link PlacedNpcComponent} and recording the ledger row.
     *
     * <p>The stamp is attached on the pre-add {@code Holder}, so the NPC is never briefly resident
     * without knowing what it is; the ledger row is written from the post-spawn hook, where the
     * entity's uuid is readable.
     *
     * @return true when the spawn succeeded
     */
    public static boolean place(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull NpcPlacementAsset placement, @Nonnull AnchorPosition position) {
        String placementId = placement.getId();
        if (placementId == null || placementId.isBlank()) {
            return false;
        }
        String role = roleFor(placement);
        if (role == null) {
            SafeLog.warn("[placement] '" + placementId + "' has no usable role - not placing");
            return false;
        }

        String worldName = worldName(world);
        String anchorKey = position.anchorKey();
        NpcPlacementAsset.Lifecycle lifecycle = placement.getLifecycle();
        boolean keepAlive = lifecycle != null && lifecycle.effectiveKeepAlive();
        boolean fortify = lifecycle != null && lifecycle.effectiveFortify();
        double fortifyHealth = lifecycle == null
                ? NpcPlacementAsset.Lifecycle.DEFAULT_FORTIFY_HEALTH
                : lifecycle.effectiveFortifyHealth();

        PlacedNpcIdentity identity = PlacedNpcIdentity.of(placementId, namespaceOf(placementId),
                selectorNameFor(world, placement), anchorKey, keepAlive, System.currentTimeMillis());

        boolean spawned = NpcSpawnService.spawnRole(world, store, role,
                new Vector3d(position.x(), position.y(), position.z()), position.yaw(),
                (npc, holder, st) -> {
                    var type = PlacedNpcComponent.getComponentType();
                    if (type != null) {
                        holder.addComponent(type, PlacedNpcComponent.of(identity));
                    }
                },
                (npc, ref, st) -> {
                    try {
                        UUIDComponent uuidComponent = st.getComponent(ref, UUIDComponent.getComponentType());
                        if (uuidComponent != null) {
                            NpcPlacementLedger.getInstance()
                                    .record(worldName, placementId, anchorKey, uuidComponent.getUuid());
                        }
                    } catch (Throwable t) {
                        SafeLog.warn("[placement] could not record the ledger row for '" + placementId
                                + "': " + t.getMessage());
                    }
                    if (fortify) {
                        fortify(st, ref, fortifyHealth);
                    }
                });

        if (!spawned) {
            return false;
        }

        NpcPlacementPositionCache.record(worldName, placementId, anchorKey,
                position.x(), position.y(), position.z());
        if (keepAlive) {
            pinChunk(world, placementId, anchorKey, position.x(), position.z());
        }
        return true;
    }

    /**
     * Which NPC role a placement spawns: its explicit {@code Identity.Role}, else the role
     * generated for it from {@code Identity.Appearance}, else {@code null}.
     */
    @Nullable
    public static String roleFor(@Nonnull NpcPlacementAsset placement) {
        NpcPlacementAsset.Identity identity = placement.getIdentity();
        if (identity == null) {
            return null;
        }
        String role = identity.getRole();
        if (role != null && !role.isBlank()) {
            return role.trim();
        }
        String placementId = placement.getId();
        if (placementId != null && identity.usesGeneratedRole() && NpcRoleGenerator.wasGenerated(placementId)) {
            return NpcRoleGenerator.generatedRoleName(placementId);
        }
        return null;
    }

    // ==================== despawn ====================

    /**
     * Remove the NPC recorded for one placement instance and drop its bookkeeping (ledger row,
     * chunk pin, cached position). Safe when the entity is already gone.
     *
     * @return true when a resident entity was actually removed
     */
    public static boolean despawn(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull String placementId, @Nonnull String anchorKey) {
        String worldName = worldName(world);
        UUID uuid = NpcPlacementLedger.getInstance().uuidOf(worldName, placementId, anchorKey);
        boolean removed = uuid != null && removeByUuid(store, uuid);
        releaseInstance(world, placementId, anchorKey);
        return removed;
    }

    /** Remove a resident entity by uuid. World thread only. */
    public static boolean removeByUuid(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid) {
        try {
            EntityStore external = store.getExternalData();
            Ref<EntityStore> ref = external.getRefFromUUID(uuid);
            if (ref == null || !ref.isValid()) {
                return false;
            }
            store.removeEntity(ref, RemoveReason.REMOVE);
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[placement] despawn failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Drop every trace of one placement instance without touching the entity: the ledger row, the
     * cached position, and the chunk pin. The despawn path calls it after removing the entity; the
     * reconciler calls it directly when it removed the entity through its own command buffer.
     */
    public static void releaseInstance(@Nonnull World world, @Nonnull String placementId,
            @Nonnull String anchorKey) {
        String worldName = worldName(world);
        NpcPlacementPositionCache.Entry cached =
                NpcPlacementPositionCache.get(worldName, placementId, anchorKey);
        if (cached != null) {
            unpinChunk(world, placementId, anchorKey, cached.x(), cached.z());
        }
        NpcPlacementPositionCache.forget(worldName, placementId, anchorKey);
        NpcPlacementLedger.getInstance().drop(worldName, placementId, anchorKey);
    }

    // ==================== lifecycle knobs ====================

    /**
     * Raise a placed NPC's max health enormously and fill the enlarged pool.
     *
     * <p><b>Why a health pool rather than the role's own {@code Invulnerable} flag.</b> The engine
     * damage pipeline does honour that flag unconditionally, but it is NOT consulted by a direct
     * write to the entity's stat map, so an effect that subtracts health itself walks straight past
     * it and can kill a service NPC, taking every player's access to whatever that NPC offers with
     * it. There is no pre-write hook to intercept that, so the mitigation is a pool no such write
     * drains. Applied additively on top of whatever the role declares, using the same
     * {@code StaticModifier} mechanism the engine's own NPC balancing uses, so it folds with the
     * role's health rather than fighting it.
     *
     * <p>Never throws: a stat-less entity simply keeps the role's own health.
     */
    public static void fortify(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, double bonus) {
        try {
            EntityStatMap stats = store.getComponent(ref,
                    EntityStatsModule.get().getEntityStatMapComponentType());
            if (stats == null) {
                return;
            }
            int healthIndex = DefaultEntityStatTypes.getHealth();
            stats.putModifier(healthIndex, FORTIFY_MODIFIER,
                    new StaticModifier(Modifier.ModifierTarget.MAX,
                            StaticModifier.CalculationType.ADDITIVE, (float) bonus));
            stats.maximizeStatValue(healthIndex);
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not fortify a placed NPC: " + t.getMessage());
        }
    }

    /** Pin the chunk holding one placement instance (see {@link PlacementKeepAlivePins}). */
    public static boolean pinChunk(@Nonnull World world, @Nonnull String placementId, @Nonnull String anchorKey,
            double x, double z) {
        return PlacementKeepAlivePins.pin(world, instanceKey(placementId, anchorKey), x, z);
    }

    /** Release one placement instance's claim on its chunk. */
    public static boolean unpinChunk(@Nonnull World world, @Nonnull String placementId, @Nonnull String anchorKey,
            double x, double z) {
        return PlacementKeepAlivePins.unpin(world, instanceKey(placementId, anchorKey), x, z);
    }

    /** The pin-table key for one placement instance. */
    @Nonnull
    public static String instanceKey(@Nonnull String placementId, @Nonnull String anchorKey) {
        return placementId + '|' + anchorKey;
    }

    // ==================== chunk state ====================

    /**
     * Is the chunk containing {@code (x, z)} loaded and ticking?
     *
     * <p>The reconciler's place rule hangs on this: an entity is REMOVED from the store while its
     * chunk sleeps, so "no entity here" only means something when the chunk is awake.
     */
    public static boolean isChunkLoaded(@Nonnull World world, double x, double z) {
        try {
            return world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z)) != null;
        } catch (Throwable t) {
            SafeLog.fine("[placement] chunk-loaded check failed: " + t.getMessage());
            return false;
        }
    }

    // ==================== helpers ====================

    /** The world's name, or an empty string when it cannot be read. */
    @Nonnull
    public static String worldName(@Nullable World world) {
        try {
            String name = world == null ? null : world.getName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Which selector name a placement matched this world on, recorded on the NPC purely so a later
     * sweep can report why it is (or is no longer) here. Falls back to the first name the world
     * carries, then to an empty string.
     */
    @Nonnull
    private static String selectorNameFor(@Nonnull World world, @Nonnull NpcPlacementAsset placement) {
        try {
            Set<String> worldNames = WorldIdentity.namesFor(world);
            String[] wanted = placement.getWhere() == null ? null : placement.getWhere().getNames();
            if (wanted != null) {
                for (String name : wanted) {
                    if (name != null && worldNames.contains(name.trim().toLowerCase(java.util.Locale.ROOT))) {
                        return name.trim().toLowerCase(java.util.Locale.ROOT);
                    }
                }
            }
            return worldNames.isEmpty() ? "" : worldNames.iterator().next();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * The mod namespace a placement belongs to, taken from its id prefix ({@code mmo_hub} to
     * {@code mmo}). Diagnostics only: it lets a listing group by mod without this library holding
     * a registry of who owns which placement.
     */
    @Nonnull
    private static String namespaceOf(@Nonnull String placementId) {
        int underscore = placementId.indexOf('_');
        return underscore > 0 ? placementId.substring(0, underscore) : "";
    }
}
