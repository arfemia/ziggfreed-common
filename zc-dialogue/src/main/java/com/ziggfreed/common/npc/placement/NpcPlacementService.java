package com.ziggfreed.common.npc.placement;

import java.util.Locale;
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
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.npc.NpcSpawnService;
import com.ziggfreed.common.util.SafeLog;

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
                matchedWorldFor(world), anchorKey, keepAlive, System.currentTimeMillis());

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
     * Which NPC role a placement spawns: its {@code Identity.Role}, or {@code null} when it names
     * none and so has nothing to stand up.
     */
    @Nullable
    public static String roleFor(@Nonnull NpcPlacementAsset placement) {
        NpcPlacementAsset.Identity identity = placement.getIdentity();
        if (identity == null || !identity.namesRole()) {
            return null;
        }
        return identity.getRole().trim();
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
            return chunkIfLoaded(world, ChunkUtil.indexChunkFromBlock(x, z)) != null;
        } catch (Throwable t) {
            SafeLog.fine("[placement] chunk-loaded check failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Ask the engine to bring the chunk containing {@code (x, z)} in and start it ticking, running
     * {@code onLoaded} on the world thread once it is there.
     *
     * <p>An anchor can resolve a perfectly good position in a chunk NOTHING has any reason to load:
     * a world spawn point no player has walked to, a structure sighted from a distance. Waiting for
     * that chunk to wake on its own means waiting forever, and the NPC that belongs there is simply
     * never placed. So the position itself is treated as the reason to load it - the same way the
     * first-party portal spawn finder loads its candidate chunks before choosing one.
     *
     * <p><b>Nothing is pinned here, deliberately.</b> The request only starts the chunk ticking; it
     * adds no keep-loaded count, so once the placement has spawned and no player is nearby the
     * engine's own unload gate lets the chunk go cold and unload again on its ordinary schedule,
     * carrying the placed NPC with it. That is the steady state the sweep is built around: the
     * ledger row outlives the chunk, and the NPC comes back with it. A placement that genuinely
     * needs its chunk held awake says so with {@code Lifecycle.KeepAlive}, which is the ONE knob
     * that takes a real pin (see {@link PlacementKeepAlivePins}).
     *
     * @return true when the request was handed to the engine
     */
    public static boolean requestChunk(@Nonnull World world, double x, double z, @Nonnull Runnable onLoaded) {
        try {
            long index = ChunkUtil.indexChunkFromBlock(x, z);
            world.getChunkStore()
                    .getChunkReferenceAsync(index, GetChunkFlags.SET_TICKING | GetChunkFlags.HIGH_PRIORITY)
                    .thenAcceptAsync(reference -> onLoaded.run(), world);
            return true;
        } catch (Throwable t) {
            SafeLog.fine("[placement] chunk load request failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * The {@code WorldChunk} at {@code index}, or {@code null} when it is not resident or is
     * resident but not ticking. Package-visible so {@link PlacementKeepAlivePins} shares the same
     * read instead of re-deriving it.
     */
    @Nullable
    static WorldChunk chunkIfLoaded(@Nonnull World world, long index) {
        Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(index);
        if (chunkRef == null || !chunkRef.isValid()) {
            return null;
        }
        WorldChunk chunk = world.getChunkStore().getStore().getComponent(chunkRef, WorldChunk.getComponentType());
        return chunk != null && chunk.is(ChunkFlag.TICKING) ? chunk : null;
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
     * The world a placement matched when it was placed, lower-cased and recorded on the NPC purely
     * so a later sweep can report why it is (or is no longer) here. An unreadable world records an
     * empty string rather than failing the placement.
     */
    @Nonnull
    private static String matchedWorldFor(@Nonnull World world) {
        try {
            String name = world.getName();
            return name == null ? "" : name.toLowerCase(Locale.ROOT);
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
