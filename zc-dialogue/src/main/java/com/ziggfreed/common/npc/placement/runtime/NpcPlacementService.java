package com.ziggfreed.common.npc.placement.runtime;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.npc.NpcSpawnService;
import com.ziggfreed.common.npc.placement.anchor.AnchorPosition;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.registry.PlacementGates;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections.SectionPos;

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
     * <p>{@code Fortify} is not applied here: {@link PlacementFortifySystem} applies it as the NPC enters
     * the store, so a copy the engine parks (its post-spawn never runs) or one that comes back as a load
     * carries it too.
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
                    // Fortify is not applied here: PlacementFortifySystem applies it at the add, before
                    // anything could park the NPC, and a parked add never reaches this callback.
                });

        if (!spawned) {
            return false;
        }

        NpcPlacementPositionCache.record(worldName, placementId, anchorKey,
                position.x(), position.y(), position.z());
        boolean keptLoaded = false;
        if (keepAlive) {
            pinChunk(world, placementId, anchorKey, position.x(), position.z());
            keptLoaded = PlacementKeepAlivePins.holdsClaim(world, instanceKey(placementId, anchorKey));
        }
        // One line per NPC placed: placing is rare (once per instance per world, then the ledger row
        // holds it), and a boot capture reads which placements went in where.
        SafeLog.info("[placement] placed '" + placementId + "' in '" + worldName + "' at " + anchorKey + " ("
                + Math.round(position.x()) + "," + Math.round(position.y()) + "," + Math.round(position.z())
                + ")" + (keepAlive ? (keptLoaded ? ", its chunk kept loaded" : ", its chunk could not be kept loaded")
                        : ""));
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
     * <p>Always fills the enlarged pool. The library applies the bonus through {@code applyFortify}
     * ({@link PlacementFortifySystem} at the add, the sweep's {@code upkeep}), which fills it only on a
     * fresh spawn or when the bonus was missing; this method is kept for a consumer linking 2.2.0.
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
            applyFortify(stats, bonus, true);
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not fortify a placed NPC: " + t.getMessage());
        }
    }

    /**
     * Apply {@code bonus} as the {@code Fortify} max-health modifier on {@code stats}. Idempotent: the
     * modifier sits under one fixed key, so a second call replaces it with itself, and the enlarged pool
     * is filled only on a fresh spawn or when the bonus was not there before ({@link #fillsPool}), so a
     * copy that already had it keeps its current health. The one Fortify write behind {@link #fortify},
     * {@link PlacementFortifySystem} and {@link #upkeep}.
     */
    static void applyFortify(@Nonnull EntityStatMap stats, double bonus, boolean freshSpawn) {
        int healthIndex = DefaultEntityStatTypes.getHealth();
        Modifier before = stats.putModifier(healthIndex, FORTIFY_MODIFIER,
                new StaticModifier(Modifier.ModifierTarget.MAX,
                        StaticModifier.CalculationType.ADDITIVE, (float) bonus));
        if (fillsPool(freshSpawn, before != null)) {
            stats.maximizeStatValue(healthIndex);
        }
    }

    /**
     * Whether applying {@code Fortify} fills the enlarged pool: on a fresh spawn, or when the bonus was
     * missing (a copy back from a park or an older build). Package-private for the test.
     */
    static boolean fillsPool(boolean freshSpawn, boolean bonusWasThere) {
        return freshSpawn || !bonusWasThere;
    }

    /** What the sweep's upkeep does for one copy it keeps. Package-private for the test. */
    record Upkeep(boolean recordPosition, boolean pin, boolean fortify) {
    }

    /**
     * The upkeep for one copy the sweep keeps: record its position unless one is cached (the cached one
     * is where its pin was taken, and {@link #releaseInstance} unpins there), take its keep-alive pin when
     * the placement keeps its chunk loaded and the instance holds no claim yet, and apply {@code Fortify}
     * when the placement authors it. Package-private for the test.
     */
    @Nonnull
    static Upkeep upkeepFor(boolean positionCached, boolean pinClaimed, boolean keepAlive, boolean fortify) {
        return new Upkeep(!positionCached, keepAlive && !pinClaimed, fortify);
    }

    /**
     * Give one copy the sweep keeps (a KEEP, or the one adoption per instance) what {@link #place} gives a
     * fresh one: its cached position (from its transform), its keep-alive pin (at the cached position) and
     * its {@code Fortify} bonus. A copy back from a park, or one met after a restart (the pin table and the
     * position cache live in memory), never ran place's bookkeeping. Only a copy live when a sweep runs is
     * reached: after a restart the boot sweep finds the hub parked and skips it, so its pin returns at a
     * later sweep that finds it awake ({@code Fortify} is saved with the entity). World thread, in the sweep's world
     * task, outside any system's processing window; each step is idempotent ({@link #upkeepFor},
     * {@link #applyFortify}). Never throws.
     */
    static void upkeep(@Nonnull World world, @Nonnull Store<EntityStore> store, @Nonnull String worldName,
            @Nullable NpcPlacementAsset placement, @Nonnull String placementId, @Nonnull String anchorKey,
            @Nonnull UUID uuid) {
        try {
            Ref<EntityStore> ref = store.getExternalData().getRefFromUUID(uuid);
            if (ref == null || !ref.isValid()) {
                return;
            }
            NpcPlacementAsset.Lifecycle lifecycle = placement == null ? null : placement.getLifecycle();
            NpcPlacementPositionCache.Entry cached =
                    NpcPlacementPositionCache.get(worldName, placementId, anchorKey);
            Upkeep step = upkeepFor(cached != null,
                    PlacementKeepAlivePins.holdsClaim(world, instanceKey(placementId, anchorKey)),
                    lifecycle != null && lifecycle.effectiveKeepAlive(),
                    lifecycle != null && lifecycle.effectiveFortify());
            if (step.fortify() && lifecycle != null) {
                EntityStatMap stats = store.getComponent(ref,
                        EntityStatsModule.get().getEntityStatMapComponentType());
                if (stats != null) {
                    applyFortify(stats, lifecycle.effectiveFortifyHealth(), false);
                }
            }
            if (step.recordPosition()) {
                TransformComponent at = store.getComponent(ref, TransformComponent.getComponentType());
                if (at == null) {
                    return;
                }
                NpcPlacementPositionCache.record(worldName, placementId, anchorKey,
                        at.getPosition().x, at.getPosition().y, at.getPosition().z);
                cached = NpcPlacementPositionCache.get(worldName, placementId, anchorKey);
            }
            if (step.pin() && cached != null) {
                pinChunk(world, placementId, anchorKey, cached.x(), cached.z());
            }
        } catch (Throwable t) {
            SafeLog.fine("[placement] upkeep failed for '" + placementId + "' at " + anchorKey + ": "
                    + t.getMessage());
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
     * Is the chunk COLUMN containing {@code (x, z)} resident and ticking?
     *
     * <p>Kept for a consumer linking 2.2.0; the library no longer asks it. On Update 7 a column's ticking
     * flag does not say whether an entity added in it stays: a chunk section loads asleep whatever its
     * column does, and an entity added into a sleeping section is parked on the spot. The reconciler reads
     * the anchor's own section through zc-world's {@code world/TickingSections.stateAt}.
     */
    public static boolean isChunkLoaded(@Nonnull World world, double x, double z) {
        try {
            WorldChunk chunk = residentChunk(world, ChunkUtil.indexChunkFromBlock(x, z));
            return chunk != null && chunk.is(ChunkFlag.TICKING);
        } catch (Throwable t) {
            SafeLog.fine("[placement] chunk-loaded check failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Ask the engine to bring the chunk containing {@code (x, z)} in and start it ticking, running
     * {@code onLoaded} on the world thread once it is there.
     *
     * <p><b>A column, not a section.</b> Kept for a consumer linking 2.2.0; the library no longer calls
     * it. On Update 7 this starts the column ticking but none of its sections (a section loads asleep,
     * and only a section request carrying {@code SET_TICKING} or a player's hot sphere wakes one), so an
     * entity added there is still parked. The reconciler requests the anchor's own section through
     * zc-world's {@code world/TickingSections.wake}.
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
     * The {@code WorldChunk} at {@code index} when it is resident, ticking or not; null when it is not.
     * Package-visible so {@link PlacementKeepAlivePins} pins and unpins through the same read. A pin keeps
     * a column resident, and the engine stops a held column ticking once its active timer runs out, so a
     * read that also asked for ticking would refuse a pin taken after a section wake (which ticks the
     * section, not its column) and skip an unpin, leaking the keep-loaded count.
     */
    @Nullable
    static WorldChunk residentChunk(@Nonnull World world, long index) {
        Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(index);
        if (chunkRef == null || !chunkRef.isValid()) {
            return null;
        }
        return world.getChunkStore().getStore().getComponent(chunkRef, WorldChunk.getComponentType());
    }

    /**
     * The placement instances ({@link #instanceKey}) with a copy held in chunk {@code section}: its parked
     * or saved holders, which come back as loads when the section wakes ({@code EntitySection
     * .getEntityHolders()}). Read off each holder's {@code ZiggfreedCommon:PlacedNpc} stamp, since a copy
     * that was parked or saved has no ledger row to name it. Never loads, never wakes; empty when the
     * section is not in memory or cannot be read. World thread only.
     */
    @Nonnull
    static Set<String> heldInstances(@Nonnull World world, @Nonnull SectionPos section) {
        Set<String> held = new HashSet<>();
        try {
            ComponentType<EntityStore, PlacedNpcComponent> type = PlacedNpcComponent.getComponentType();
            if (type == null) {
                return held;
            }
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> sectionRef = chunks.getChunkSectionReference(section.x(), section.y(), section.z());
            if (sectionRef == null || !sectionRef.isValid()) {
                return held;
            }
            EntitySection entities = chunks.getStore().getComponent(sectionRef, EntitySection.getComponentType());
            if (entities == null) {
                return held;
            }
            for (Holder<EntityStore> holder : entities.getEntityHolders()) {
                PlacedNpcComponent placed = holder.getComponent(type);
                PlacedNpcIdentity identity = placed == null ? PlacedNpcIdentity.UNKNOWN : placed.toIdentity();
                if (!identity.isUnknown()) {
                    held.add(instanceKey(identity.placementId(), identity.anchorKey()));
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not read the NPCs held in chunk section " + section + ": "
                    + t.getMessage());
        }
        return held;
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
