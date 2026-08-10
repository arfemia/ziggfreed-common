package com.ziggfreed.common.npc.placement;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.npc.placement.PlacementGate.GateVerdict;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.WorldIdentity;

/**
 * Brings a world into agreement with what the placement content says should be standing in it.
 *
 * <p><b>Read this before changing anything here: NEVER place from absence alone.</b> A chunk
 * unload REMOVES an entity from the store and restores it when the chunk ticks again, so a sweep
 * over resident entities cannot tell "never placed" from "placed, chunk asleep". Placing on
 * absence spawns a second NPC every single time a player walks back into range, which duplicates
 * every placement in the world over an afternoon and is the exact bug this whole design exists to
 * prevent. A placement therefore requires BOTH a ledger miss AND a loaded anchor chunk.
 * {@code Lifecycle.KeepAlive} hides the problem for one placement and for nothing else, so it is
 * not a fix.
 *
 * <p><b>Two authorities, because neither can do the other's job.</b>
 * <ul>
 *   <li>{@link PlacedNpcComponent} on a resident entity answers "what is standing that should not
 *       be" - the placement was deleted, its gate now denies, its {@code Where} no longer matches.
 *       Only a resident entity can be asked this.</li>
 *   <li>{@link NpcPlacementLedger} answers "what has already been placed" - which survives both
 *       the chunk sleeping and the server restarting. Only a persisted row can answer this.</li>
 * </ul>
 *
 * <p>The sweep runs three passes in this order, and the order matters: DESPAWN first (so a removed
 * placement frees its {@code MaxPerWorld} slot in the same pass), then HEAL (so an NPC from an
 * older build is adopted rather than duplicated), then PLACE.
 *
 * <p><b>Debounce.</b> A world entry is a common event and a full parallel entity scan is not free,
 * so each world is swept once and then latched. Anything that can change the answer clears the
 * latch: an asset reload, a gate change, a new structure sighting, a zone discovery, world removal.
 *
 * <p>Every sweep is deferred onto the world's task queue, so it never runs inside a system's
 * processing window (spawning an entity from inside one throws, and the throw would be swallowed
 * into a silently missing NPC).
 */
public final class NpcPlacementReconciler {

    // ==================== pure decision cores ====================

    /** What to do with an NPC that is standing right now. */
    public enum ResidentDecision {
        /** Leave it alone. */
        KEEP,
        /** Remove it: it should not be here any more. */
        DESPAWN,
        /** Keep it, but hand it back so the caller can re-stamp its ledger row. */
        REBIND
    }

    /**
     * What the resident decision is made from.
     *
     * @param placementKnown  does the placement it claims still exist?
     * @param gateAllowed     does the gate chain still allow it here?
     * @param whereMatches    does the placement's {@code Where} still match this world?
     * @param ledgerRowMatches does a ledger row point at THIS entity for this instance?
     */
    public record ResidentInputs(boolean placementKnown, boolean gateAllowed, boolean whereMatches,
                                 boolean ledgerRowMatches) {
    }

    /**
     * The resident policy. PURE, so every branch is unit-testable without a live store.
     *
     * <p>A REBIND rather than a despawn for a row mismatch is deliberate: an NPC that is standing
     * correctly but has no row is an NPC from an older build (or one whose row was lost), and
     * removing it just to place an identical one is a visible flicker for no gain.
     */
    @Nonnull
    public static ResidentDecision decideResident(@Nonnull ResidentInputs in) {
        if (!in.placementKnown() || !in.gateAllowed() || !in.whereMatches()) {
            return ResidentDecision.DESPAWN;
        }
        return in.ledgerRowMatches() ? ResidentDecision.KEEP : ResidentDecision.REBIND;
    }

    /** What to do about one resolved anchor position. */
    public enum PlaceDecision {
        /** Nothing is placed here and the chunk is awake: place it. */
        PLACE,
        /** A row exists, the chunk is awake, and the NPC is genuinely gone: place it again. */
        REPLACE,
        /** Do nothing this pass. */
        SKIP
    }

    /**
     * What the place decision is made from.
     *
     * @param gateAllowed       does the gate chain allow this placement here?
     * @param whereMatches      does the placement's {@code Where} match this world?
     * @param ledgerHit         is there already a row for this instance?
     * @param anchorChunkLoaded is the anchor's chunk loaded and ticking?
     * @param entityResident    is the row's entity actually present? (only meaningful with the
     *                          chunk loaded, which is why the rule below checks that first)
     * @param respawn           does the placement opt into being placed again after loss?
     * @param atCapacity        has {@code MaxPerWorld} already been reached in this world?
     * @param claimInFlight     is another pass already placing this exact instance?
     */
    public record PlaceInputs(boolean gateAllowed, boolean whereMatches, boolean ledgerHit,
                              boolean anchorChunkLoaded, boolean entityResident, boolean respawn,
                              boolean atCapacity, boolean claimInFlight) {
    }

    /**
     * The place policy. PURE, and the one place the never-place-from-absence rule is written down.
     *
     * <p>The two SKIP branches that look like they could be a placement are the whole point:
     * <ul>
     *   <li>no row and the chunk is ASLEEP: the anchor cannot even be trusted, let alone the
     *       absence of an entity at it;</li>
     *   <li>a row and the chunk is ASLEEP: the NPC is almost certainly there, just not resident.
     *       This is the branch that duplicates the world's NPCs if it ever returns PLACE.</li>
     * </ul>
     */
    @Nonnull
    public static PlaceDecision decidePlace(@Nonnull PlaceInputs in) {
        if (!in.gateAllowed() || !in.whereMatches() || in.claimInFlight()) {
            return PlaceDecision.SKIP;
        }
        if (!in.anchorChunkLoaded()) {
            // Asleep. Absence proves nothing here, in either direction.
            return PlaceDecision.SKIP;
        }
        if (!in.ledgerHit()) {
            return in.atCapacity() ? PlaceDecision.SKIP : PlaceDecision.PLACE;
        }
        if (in.entityResident()) {
            return PlaceDecision.SKIP;
        }
        // A row, an awake chunk, and no entity: it is genuinely gone.
        return in.respawn() ? PlaceDecision.REPLACE : PlaceDecision.SKIP;
    }

    // ==================== sweep state ====================

    /** Worlds already swept since the last invalidation. */
    private static final Set<World> SWEPT = ConcurrentHashMap.newKeySet();

    /** Instances currently being placed, keyed {@code world|placementId|anchorKey}. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    static {
        WorldEvictors.registerEvictor(NpcPlacementReconciler::onWorldRemoved);
    }

    private NpcPlacementReconciler() {
    }

    /** Clear every world's debounce latch (an asset reload changed what the answer is). */
    public static void clearDebounce() {
        SWEPT.clear();
    }

    /** Clear one world's debounce latch (a gate change, a new sighting, a zone discovery). */
    public static void clearDebounce(@Nullable World world) {
        if (world != null) {
            SWEPT.remove(world);
        }
    }

    /** Has {@code world} been swept since the last invalidation? (diagnostics, tests) */
    public static boolean isLatched(@Nullable World world) {
        return world != null && SWEPT.contains(world);
    }

    /**
     * Drop a removed world's sweep state. Also drops its ledger rows and cached positions: an
     * instance world is destroyed outright and is never coming back under the same name, so a row
     * for it would be a permanent orphan.
     */
    public static void onWorldRemoved(@Nullable World world) {
        if (world == null) {
            return;
        }
        SWEPT.remove(world);
        String name = NpcPlacementService.worldName(world);
        if (!name.isEmpty()) {
            IN_FLIGHT.removeIf(k -> k.startsWith(name + '|'));
            NpcPlacementLedger.getInstance().dropWorld(name);
            NpcPlacementPositionCache.forgetWorld(name);
        }
    }

    // ==================== triggers ====================

    /**
     * Sweep {@code world} unless it is already latched. The trigger every ordinary moment uses
     * (a world being added, a player becoming ready, a player entering a world).
     */
    public static void requestSweep(@Nonnull World world, @Nonnull Store<EntityStore> store) {
        if (!SWEPT.add(world)) {
            return;
        }
        defer(world);
    }

    /**
     * Sweep {@code world} now, whatever the latch says. The trigger for a moment that CHANGED the
     * answer: an admin enable or disable, an asset reload, an explicit reconcile command.
     */
    public static void forceSweep(@Nonnull World world, @Nonnull Store<EntityStore> store) {
        SWEPT.add(world);
        defer(world);
    }

    private static void defer(@Nonnull World world) {
        try {
            world.execute(() -> {
                try {
                    sweep(world, world.getEntityStore().getStore());
                } catch (Throwable t) {
                    SafeLog.warn("[placement] sweep failed for world '"
                            + NpcPlacementService.worldName(world) + "': " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[placement] could not schedule a sweep: " + t.getMessage());
        }
    }

    // ==================== the sweep ====================

    /** What one sweep did. */
    public record SweepSummary(int scanned, int despawned, int rebound, int placed) {
    }

    /**
     * Bring {@code world} into agreement with the placement content. WORLD-THREAD ONLY, and never
     * from inside a system's processing window (use {@link #requestSweep} / {@link #forceSweep},
     * which defer for you). Never throws.
     */
    @Nonnull
    public static SweepSummary sweep(@Nonnull World world, @Nonnull Store<EntityStore> store) {
        String worldName = NpcPlacementService.worldName(world);
        NpcPlacementLedger ledger = NpcPlacementLedger.getInstance();

        DespawnPass despawnPass = runDespawnPass(world, store, worldName);
        int rebound = runHealPass(world, store, worldName, ledger);
        int placed = runPlacePass(world, store, worldName, ledger);

        return new SweepSummary(despawnPass.scanned, despawnPass.despawned, rebound, placed);
    }

    private record DespawnPass(int scanned, int despawned) {
    }

    /**
     * Pass 1, component-authoritative: remove every standing placed NPC that should not be here.
     * Runs over the store's own parallel iteration and removes through its command buffer, the
     * first-party pattern for a bulk removal.
     */
    @Nonnull
    private static DespawnPass runDespawnPass(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull String worldName) {
        ComponentType<EntityStore, PlacedNpcComponent> type = PlacedNpcComponent.getComponentType();
        if (type == null) {
            return new DespawnPass(0, 0);
        }
        NpcPlacementConfig config = NpcPlacementConfig.getInstance();
        NpcPlacementLedger ledger = NpcPlacementLedger.getInstance();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger despawned = new AtomicInteger();
        ConcurrentLinkedQueue<PlacedNpcIdentity> removedInstances = new ConcurrentLinkedQueue<>();

        // Resolve every placement's verdict ONCE, before the walk. A gate may consult a consumer's
        // factor provider, and running that per entity inside a parallel iteration would be both
        // wasteful and a needless invitation for third-party code to touch the store mid-walk.
        Map<String, Boolean> standingAllowed = new ConcurrentHashMap<>();
        for (NpcPlacementAsset placement : config.all().values()) {
            if (placement == null || placement.getId() == null) {
                continue;
            }
            boolean allowed = !PlacementGates.decide(placement, world, store).isDenied()
                    && matchesWorld(placement, world);
            standingAllowed.put(placement.getId(), allowed);
        }

        try {
            store.forEachEntityParallel(type, (index, chunk, cmdBuffer) -> {
                try {
                    PlacedNpcComponent component = chunk.getComponent(index, type);
                    if (component == null) {
                        return;
                    }
                    scanned.incrementAndGet();
                    PlacedNpcIdentity identity = component.toIdentity();
                    if (identity.isUnknown()) {
                        return;
                    }

                    Boolean allowed = standingAllowed.get(identity.placementId());
                    boolean known = allowed != null;
                    boolean rowMatches = ledger.hasRow(worldName, identity.placementId(), identity.anchorKey());

                    // The gate and the world match are folded into one pre-resolved boolean above,
                    // so both inputs carry it; the pure core still distinguishes "unknown".
                    ResidentDecision decision = decideResident(new ResidentInputs(
                            known, known && allowed, known && allowed, rowMatches));
                    if (decision == ResidentDecision.DESPAWN) {
                        cmdBuffer.tryRemoveEntity(chunk.getReferenceTo(index), RemoveReason.REMOVE);
                        despawned.incrementAndGet();
                        removedInstances.add(identity);
                    }
                } catch (Throwable perEntity) {
                    SafeLog.fine("[placement] despawn pass, per-entity failure: " + perEntity.getMessage());
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[placement] despawn pass failed: " + t.getMessage());
        }

        // Bookkeeping happens OUTSIDE the iteration: dropping a ledger row writes a file, and the
        // pin release reads a chunk, neither of which belongs inside a parallel entity walk.
        for (PlacedNpcIdentity identity : removedInstances) {
            NpcPlacementService.releaseInstance(world, identity.placementId(), identity.anchorKey());
        }
        return new DespawnPass(scanned.get(), despawned.get());
    }

    /**
     * Pass 2: adopt a resident NPC that a ledger row points at but which carries no stamp (an NPC
     * placed by an older build). Re-stamping is what keeps the next pass from placing a second one
     * beside it.
     */
    private static int runHealPass(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull String worldName, @Nonnull NpcPlacementLedger ledger) {
        ComponentType<EntityStore, PlacedNpcComponent> type = PlacedNpcComponent.getComponentType();
        if (type == null) {
            return 0;
        }
        int healed = 0;
        for (NpcPlacementLedger.Row row : ledger.rowsInWorld(worldName)) {
            try {
                Ref<EntityStore> ref = store.getExternalData().getRefFromUUID(row.uuid());
                if (ref == null || !ref.isValid()) {
                    continue; // Not resident: asleep or gone. The place pass decides, not this one.
                }
                if (store.getComponent(ref, type) != null) {
                    continue;
                }
                NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(row.placementId());
                if (placement == null) {
                    continue;
                }
                NpcPlacementAsset.Lifecycle lifecycle = placement.getLifecycle();
                store.putComponent(ref, type, PlacedNpcComponent.of(PlacedNpcIdentity.of(
                        row.placementId(), "", "", row.anchorKey(),
                        lifecycle != null && lifecycle.effectiveKeepAlive(), System.currentTimeMillis())));
                healed++;
            } catch (Throwable t) {
                SafeLog.fine("[placement] heal pass, per-row failure: " + t.getMessage());
            }
        }
        return healed;
    }

    /** Pass 3, ledger-authoritative: place what is missing, and only what is provably missing. */
    private static int runPlacePass(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull String worldName, @Nonnull NpcPlacementLedger ledger) {
        int placed = 0;
        for (NpcPlacementAsset placement : NpcPlacementConfig.getInstance().all().values()) {
            if (placement == null || placement.getId() == null || placement.getId().isBlank()) {
                continue;
            }
            String placementId = placement.getId();
            try {
                GateVerdict verdict = PlacementGates.decide(placement, world, store);
                boolean gateAllowed = !verdict.isDenied();
                boolean whereMatches = matchesWorld(placement, world);
                if (!gateAllowed || !whereMatches) {
                    continue; // Pass 1 already removed anything standing for it.
                }

                List<AnchorPosition> positions = PlacementAnchors.resolve(world, store, placement);
                if (positions.isEmpty()) {
                    continue;
                }
                NpcPlacementAsset.Limits limits = placement.getLimits();
                int max = limits == null ? 0 : limits.effectiveMaxPerWorld();
                int already = ledger.countInWorld(worldName, placementId);
                boolean respawn = placement.getLifecycle() != null
                        && placement.getLifecycle().effectiveRespawn();

                for (AnchorPosition position : positions) {
                    String anchorKey = position.anchorKey();
                    String flightKey = worldName + '|' + placementId + '|' + anchorKey;
                    boolean ledgerHit = ledger.hasRow(worldName, placementId, anchorKey);
                    boolean atCapacity = max > 0 && !ledgerHit && already >= max;

                    PlaceDecision decision = decidePlace(new PlaceInputs(
                            true, true, ledgerHit,
                            NpcPlacementService.isChunkLoaded(world, position.x(), position.z()),
                            ledgerHit && isResident(store, ledger.uuidOf(worldName, placementId, anchorKey)),
                            respawn, atCapacity, IN_FLIGHT.contains(flightKey)));
                    if (decision == PlaceDecision.SKIP) {
                        continue;
                    }

                    // Claim the instance before spawning: the entity is invisible to a concurrent
                    // pass until the command buffer flushes, so the claim set is what stops two
                    // players entering a fresh instance in one tick from both placing.
                    if (!IN_FLIGHT.add(flightKey)) {
                        continue;
                    }
                    try {
                        if (decision == PlaceDecision.REPLACE) {
                            NpcPlacementService.releaseInstance(world, placementId, anchorKey);
                        }
                        if (NpcPlacementService.place(world, store, placement, position)) {
                            placed++;
                            already++;
                        }
                    } finally {
                        IN_FLIGHT.remove(flightKey);
                    }
                }
            } catch (Throwable t) {
                SafeLog.warn("[placement] place pass failed for '" + placementId + "': " + t.getMessage());
            }
        }
        return placed;
    }

    // ==================== helpers ====================

    /**
     * Does {@code placement}'s {@code Where} match {@code world}? A null or empty selector defaults
     * to the {@code primary} selector name at THIS read site (the selector codec itself carries no
     * default, because a rules table and a placement want different ones).
     */
    public static boolean matchesWorld(@Nonnull NpcPlacementAsset placement, @Nullable World world) {
        var where = placement.getWhere();
        if (where == null || where.isBlank()) {
            return WorldIdentity.has(world, DEFAULT_WORLD_NAME);
        }
        return where.match(world) != null;
    }

    /** The selector name an unauthored {@code Where} means: the ordinary persistent world. */
    public static final String DEFAULT_WORLD_NAME = "primary";

    private static boolean isResident(@Nonnull Store<EntityStore> store, @Nullable UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            Ref<EntityStore> ref = store.getExternalData().getRefFromUUID(uuid);
            return ref != null && ref.isValid();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Drop sweep state (tests). */
    static void clearForTests() {
        SWEPT.clear();
        IN_FLIGHT.clear();
    }
}
