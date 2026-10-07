package com.ziggfreed.common.npc.placement.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.npc.placement.anchor.AnchorPosition;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.registry.PlacementGate.GateVerdict;
import com.ziggfreed.common.npc.placement.registry.PlacementGates;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections;
import com.ziggfreed.common.world.TickingSections.SectionPos;
import com.ziggfreed.common.world.WorldSelector;

/**
 * Brings a world into agreement with what the placement content says should be standing in it.
 *
 * <p><b>Read this before changing anything here: NEVER place from absence alone.</b> A chunk
 * unload REMOVES an entity from the store and restores it when the chunk ticks again, so a sweep
 * over resident entities cannot tell "never placed" from "placed, chunk asleep". Placing on
 * absence spawns a second NPC every single time a player walks back into range, which duplicates
 * every placement in the world over an afternoon and is the exact bug this whole design exists to
 * prevent. A placement therefore requires BOTH a ledger miss AND a ticking anchor chunk section (on
 * Update 7 a section sleeps whatever its column does; see the place pass).
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
        /** Keep it, and mint a ledger row pointing at it, since none exists yet. */
        REBIND
    }

    /**
     * What the resident decision is made from.
     *
     * @param placementKnown  does the placement it claims still exist?
     * @param gateAllowed     does the gate chain still allow it here?
     * @param whereMatches    does the placement's {@code Where} still match this world?
     * @param ledgerRowExists does ANY ledger row exist for this instance, whoever it names?
     * @param ledgerRowMatchesThisEntity does that row's uuid name THIS entity specifically?
     */
    public record ResidentInputs(boolean placementKnown, boolean gateAllowed, boolean whereMatches,
                                 boolean ledgerRowExists, boolean ledgerRowMatchesThisEntity) {
    }

    /**
     * The resident policy. PURE, so every branch is unit-testable without a live store.
     *
     * <p>A missing row and a row pointing at someone else are NOT the same thing, and conflating
     * them is exactly what let a duplicate stand forever: a resident entity that is correct but has
     * NO row at all is adopted (REBIND) - an older-build NPC, or one whose row was lost, and
     * removing it just to place an identical one would be a visible flicker for no gain. A resident
     * entity whose (placement, anchor) row EXISTS and names a DIFFERENT uuid is a surplus duplicate
     * left behind by an earlier REPLACE (the recorded entity was briefly unresident when a sweep
     * ran, so a new one was placed and the row re-pointed to it) and must be DESPAWNED, or the world
     * accumulates one more of it every time that happens.
     */
    @Nonnull
    public static ResidentDecision decideResident(@Nonnull ResidentInputs in) {
        if (!in.placementKnown() || !in.gateAllowed() || !in.whereMatches()) {
            return ResidentDecision.DESPAWN;
        }
        if (!in.ledgerRowExists()) {
            return ResidentDecision.REBIND;
        }
        return in.ledgerRowMatchesThisEntity() ? ResidentDecision.KEEP : ResidentDecision.DESPAWN;
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
     * @param anchorChunkLoaded is the anchor's chunk SECTION ticking, and not woken by this round? (On
     *                          Update 7 a section ticks on its own, whatever its column does; the
     *                          component keeps its 2.2.0 name for linkage.)
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

    /**
     * Ask the engine for the chunk section under {@code position}, ticking, once, and sweep again when it
     * lands.
     *
     * <p>An anchor whose section is not in memory is not a race to wait out: it is the ordinary shape of
     * a placement standing somewhere nobody has walked (a structure sighted from a distance, a spawn point
     * whose column nothing loaded). Nothing will load that section on its own, so the placement would
     * never appear however long the retry budget ran; requesting it makes the position itself the reason
     * to load it. The request carries {@code SET_TICKING}, the only request that wakes a section on
     * Update 7 (a column request wakes only its column; see {@code TickingSections}).
     *
     * <p>No pin is taken: once the NPC is placed and nobody is near, the section goes back to sleep and
     * the column unloads on the engine's own schedule, taking the NPC with them and leaving the ledger
     * row - the steady state the whole sweep is built around. Only {@code Lifecycle.KeepAlive} pins a
     * column.
     *
     * <p>Requested once per section per world: the same anchor is walked on every pass. The claim is
     * released when the request completes, landed or not, but only a landed section sweeps again
     * ({@link #sweepAfterLanding}): a section the chunk store failed to bring up is asked for again by the
     * next sweep the retry chain or a trigger runs, never by a loop of its own. A world that stops taking
     * tasks before its request completes never runs that completion, so its key stays until
     * {@link #onWorldRemoved} clears it (a stopping world sweeps no more, so nothing asks for it meanwhile).
     *
     * @return true when a request was made on this call
     */
    private static boolean requestAnchorSection(@Nonnull World world, @Nonnull String worldName,
            @Nonnull SectionPos section, @Nonnull AnchorPosition position) {
        String key = worldName + '|' + section.x() + ',' + section.y() + ',' + section.z();
        if (!SECTION_REQUESTS.add(key)) {
            return false;
        }
        TickingSections.wake(world, position.x(), position.y(), position.z())
                .whenCompleteAsync((sectionRef, error) -> {
                    SECTION_REQUESTS.remove(key);
                    if (sweepAfterLanding(sectionRef, error)) {
                        // The section is in and ticking now, so the place decision that skipped can go through.
                        defer(world);
                    } else {
                        SafeLog.fine("[placement] the chunk section " + section + " in '" + worldName
                                + "' could not be brought up" + (error == null ? "" : ": " + error));
                    }
                }, world);
        return true;
    }

    /**
     * Whether a section request's completion sweeps the world again: only when it landed with a section
     * whose ref is still valid (as {@code EncounterSpawner.spawnInLoadedSection} checks). A failed or
     * empty load, or a section gone out of memory again before this ran, does not, or the next sweep
     * would ask again, a chunk store on its failure backoff would answer at once, and the world thread
     * would spin. Package-private for the test.
     */
    static boolean sweepAfterLanding(@Nullable Ref<ChunkStore> sectionRef, @Nullable Throwable error) {
        return error == null && sectionRef != null && sectionRef.isValid();
    }

    // ==================== sweep state ====================

    /** Worlds already swept since the last invalidation. */
    private static final Set<World> SWEPT = ConcurrentHashMap.newKeySet();

    /** Instances currently being placed, keyed {@code world|placementId|anchorKey}. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * Worlds with a sweep queued or running. The pump admits ONE chain per world at a time.
     *
     * <p>Triggers are not rare: a world being walked through kicks a sweep per newly-sighted
     * structure marker, and each of those clears the debounce latch first. Letting every trigger
     * start its own retry chain means a world carrying a single placement that cannot resolve pays
     * the whole retry budget once per sighting, concurrently - overlapping chains, each re-scanning
     * every entity in the world, each ending in its own identical give-up line. One chain is
     * enough: it re-sweeps on a timer anyway, so whatever changed while it was running is picked up
     * by its next pass.
     */
    private static final Set<World> PUMPING = ConcurrentHashMap.newKeySet();

    /** Worlds whose trigger arrived mid-chain: one more sweep is owed once the chain ends. */
    private static final Set<World> RESWEEP = ConcurrentHashMap.newKeySet();

    /**
     * Worlds that spent their whole resolve-retry budget. A later trigger still sweeps - a sighting
     * or a chunk arriving can resolve what could not be resolved before - but as a ONE-SHOT pass,
     * never another chain, and the give-up is reported once per world rather than once per trigger.
     * Cleared by anything that genuinely changes the answer: a forced sweep, an asset reload, the
     * world going away.
     */
    private static final Set<World> RETRY_EXHAUSTED = ConcurrentHashMap.newKeySet();

    /**
     * Anchor chunk sections already asked for, keyed {@code worldName|x,y,z}, so a section is requested
     * once rather than on every pass over the same unplaced anchor.
     */
    private static final Set<String> SECTION_REQUESTS = ConcurrentHashMap.newKeySet();

    static {
        WorldEvictors.registerEvictor(NpcPlacementReconciler::onWorldRemoved);
    }

    private NpcPlacementReconciler() {
    }

    /** Clear every world's debounce latch (an asset reload changed what the answer is). */
    public static void clearDebounce() {
        SWEPT.clear();
        RETRY_EXHAUSTED.clear();
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
     * Drop a removed world's sweep state, and its ledger rows and cached positions only when the world
     * is deleted with its removal ({@link #isDeleted}).
     *
     * <p>A removal is not a deletion. The engine removes every world at a server stop, and a world an
     * admin unloads keeps its folder: either comes back under the same name with its placed NPCs saved
     * in its chunks, and only its rows tell the next sweep which of them were already placed. Dropping
     * them at every stop made each boot re-adopt the world's NPCs, and brought back a placement without
     * {@code Respawn} whose NPC had been killed. A deleted world (an instance or portal world torn down,
     * a pruned world) never comes back under its name, so a row for it would be a permanent orphan.
     */
    public static void onWorldRemoved(@Nullable World world) {
        if (world == null) {
            return;
        }
        SWEPT.remove(world);
        PUMPING.remove(world);
        RESWEEP.remove(world);
        RETRY_EXHAUSTED.remove(world);
        String name = NpcPlacementService.worldName(world);
        if (!name.isEmpty()) {
            WorldConfig config = world.getWorldConfig();
            forgetWorld(name, isDeleted(config.isDeleteOnRemove(), config.isDeleteOnUniverseStart()));
        }
    }

    /**
     * Whether a removed world goes with its removal, from its config's two delete flags, the engine's own
     * signals: {@code DeleteOnRemove} deletes the world's folder right after the removal event (at a
     * server stop as anywhere else), and {@code DeleteOnUniverseStart} has the next start delete the
     * folder instead of loading it. The removal reason cannot tell: a server stop removes every world as
     * {@code EXCEPTIONAL}, the reason a crash carries. Package-private for the test.
     */
    static boolean isDeleted(boolean deleteOnRemove, boolean deleteOnUniverseStart) {
        return deleteOnRemove || deleteOnUniverseStart;
    }

    /**
     * The name-keyed half of {@link #onWorldRemoved}: the world's in-flight claims and section requests
     * on any removal, its ledger rows and cached positions only when it is {@code deleted}. A cached
     * position follows its row: one whose row stayed is still true. Package-private for the test.
     */
    static void forgetWorld(@Nonnull String worldName, boolean deleted) {
        IN_FLIGHT.removeIf(k -> k.startsWith(worldName + '|'));
        SECTION_REQUESTS.removeIf(k -> k.startsWith(worldName + '|'));
        if (deleted) {
            NpcPlacementLedger.getInstance().dropWorld(worldName);
            NpcPlacementPositionCache.forgetWorld(worldName);
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
        // The content itself just changed, so a world that had given up earns its retry budget
        // back: what could not resolve before may resolve against the new answer.
        RETRY_EXHAUSTED.remove(world);
        defer(world);
    }

    /**
     * Bounded retry budget for a world carrying an unresolved anchor (see {@link PlacePass}): up
     * to N retries, each spaced a real {@link #RETRY_DELAY_MS} apart. A world genuinely done -
     * nothing left unresolved - stops retrying on its own the moment
     * {@link SweepSummary#unresolvedAnchors()} reads 0, so this budget only bounds the
     * pathological case (a race that somehow never resolves) rather than the ordinary one.
     *
     * <p>It is deliberately SHORT, because the one case that used to need a long one is now
     * answered directly instead of waited out: an anchor whose position resolved into a sleeping
     * chunk gets that chunk requested (see the place pass), and the request's completion sweeps the
     * world again. What is left for the budget to cover is the brief startup race where an anchor
     * provider has not settled yet, which resolves in the first pass or two or not at all.
     */
    private static final int MAX_RESOLVE_RETRIES = 8;

    /**
     * The wall-clock spacing between resolve retries. {@code World.execute(Runnable)} offers
     * straight onto the world's own task deque, which {@code consumeTaskQueue()} drains in a
     * {@code while (poll() != null)} loop - a task that calls {@code execute()} on itself from
     * inside that loop feeds right back into the SAME drain and runs again in the SAME tick, so a
     * bare requeue gives the engine's async work zero real time to finish. {@code World
     * .scheduleAfter} times the wait off the shared server scheduler first and only reaches the
     * task queue once the delay has genuinely elapsed, which is why every retry below goes
     * through it instead of a second {@code execute()} call.
     */
    private static final long RETRY_DELAY_MS = 500L;

    /**
     * Queue a sweep for {@code world} unless one is already queued or running, in which case the
     * running chain is simply owed one more pass when it ends (see {@link #PUMPING}).
     */
    private static void defer(@Nonnull World world) {
        if (!PUMPING.add(world)) {
            RESWEEP.add(world);
            return;
        }
        startChain(world);
    }

    /** Hand the world's own task queue the first sweep of a chain, holding the pump for it. */
    private static void startChain(@Nonnull World world) {
        RESWEEP.remove(world);
        // A world that already spent its budget sweeps once and does not chain: the trigger still
        // gets its answer, without paying for a retry burst that has already proved it cannot help.
        int budget = RETRY_EXHAUSTED.contains(world) ? 0 : MAX_RESOLVE_RETRIES;
        try {
            world.execute(() -> runSweepAndMaybeRetry(world, budget));
        } catch (Throwable t) {
            releasePump(world);
            SafeLog.warn("[placement] could not schedule a sweep: " + t.getMessage());
        }
    }

    /** Release the pump at the end of a chain, running the pass a mid-chain trigger is owed. */
    private static void releasePump(@Nonnull World world) {
        PUMPING.remove(world);
        if (RESWEEP.remove(world) && PUMPING.add(world)) {
            startChain(world);
        }
    }

    /** Runs one sweep; if it left an eligible placement unresolved, schedules a real-delay retry. */
    private static void runSweepAndMaybeRetry(@Nonnull World world, int retriesLeft) {
        try {
            SweepSummary summary = sweep(world, world.getEntityStore().getStore());
            // One INFO line per sweep that CHANGED the world; a sweep that only looked logs at
            // fine (a full retry wait would otherwise print 41 identical lines, and a marker-heavy
            // chunk load kicks a sweep per new sighting). The trail for an NPC that never appears
            // survives the quiet: each unresolved-anchor reason has its own once-per-world INFO
            // line, and exhausting the retries is the WARN below.
            String attempt = RETRY_EXHAUSTED.contains(world) ? "one-shot"
                    : (MAX_RESOLVE_RETRIES - retriesLeft + 1) + "/" + (MAX_RESOLVE_RETRIES + 1);
            String line = "[placement] sweep '" + NpcPlacementService.worldName(world) + "' (attempt "
                    + attempt
                    + "): scanned=" + summary.scanned() + " despawned=" + summary.despawned()
                    + " adopted=" + summary.rebound() + " placed=" + summary.placed()
                    + " unresolvedAnchors=" + summary.unresolvedAnchors();
            if (summary.despawned() > 0 || summary.rebound() > 0 || summary.placed() > 0) {
                SafeLog.info(line);
            } else {
                SafeLog.fine(line);
            }
            if (summary.unresolvedAnchors() == 0) {
                releasePump(world);
                return;
            }
            if (retriesLeft <= 0) {
                if (RETRY_EXHAUSTED.add(world)) {
                    SafeLog.warn("[placement] '" + NpcPlacementService.worldName(world) + "': "
                            + summary.unresolvedAnchors() + " placement(s) still could not resolve an "
                            + "anchor position after " + MAX_RESOLVE_RETRIES + " retries - no further "
                            + "retry bursts for this world (a role-agnostic WorldSpawn provider that "
                            + "never resolves, or content whose anchor genuinely never matches). A "
                            + "later sighting, chunk arrival or admin reconcile still sweeps once.");
                }
                releasePump(world);
                return;
            }
            // Not a content problem - an eligible, ledger-missing placement asked for a position
            // (e.g. Anchor.WorldSpawn's world spawn point) the engine could not yet resolve.
            // WorldConfig's own spawn provider is set asynchronously during world init and reads
            // null until that settles, which a one-shot sweep can easily lose the race against,
            // especially for a just-created instance world. requestSweep's debounce means nothing
            // else will naturally retry this world, so retry it ourselves - on a real delay.
            world.scheduleAfter(() -> runSweepAndMaybeRetry(world, retriesLeft - 1),
                    RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            releasePump(world);
            SafeLog.warn("[placement] sweep failed for world '"
                    + NpcPlacementService.worldName(world) + "': " + t.getMessage());
        }
    }

    // ==================== the sweep ====================

    /** What one sweep did. {@code unresolvedAnchors} is the retry signal - see {@link #runSweepAndMaybeRetry}. */
    public record SweepSummary(int scanned, int despawned, int rebound, int placed, int unresolvedAnchors) {
    }

    /**
     * Bring {@code world} into agreement with the placement content. WORLD-THREAD ONLY, and never
     * from inside a system's processing window (use {@link #requestSweep} / {@link #forceSweep},
     * which defer for you). Never throws.
     *
     * <p>One round, or two when the first woke an anchor's chunk section ({@link #settleRounds}).
     */
    @Nonnull
    public static SweepSummary sweep(@Nonnull World world, @Nonnull Store<EntityStore> store) {
        String worldName = NpcPlacementService.worldName(world);
        NpcPlacementLedger ledger = NpcPlacementLedger.getInstance();
        return settleRounds(() -> sweepRound(world, store, worldName, ledger));
    }

    /** One round of a sweep: what it did, and whether its place pass woke an anchor's chunk section. */
    record Round(@Nonnull SweepSummary summary, boolean wokeASection) {
    }

    /**
     * Run {@code round}, and once more when it woke an anchor's chunk section. The wake brings that
     * section's parked entities back into the world after the round's despawn pass has run, so only the
     * next round's despawn pass can adopt one before its place pass would stand another beside it; a round
     * never places into a section it woke ({@code AnchorSections}). Never more than two rounds: the counts
     * add up, and the unresolved anchors are the last round's, the retry signal for what is still open.
     * Package-private for the test.
     */
    @Nonnull
    static SweepSummary settleRounds(@Nonnull Supplier<Round> round) {
        Round first = round.get();
        if (!first.wokeASection()) {
            return first.summary();
        }
        SweepSummary a = first.summary();
        SweepSummary b = round.get().summary();
        return new SweepSummary(b.scanned(), a.despawned() + b.despawned(), a.rebound() + b.rebound(),
                a.placed() + b.placed(), b.unresolvedAnchors());
    }

    /** One round: despawn, then heal, then place. */
    @Nonnull
    private static Round sweepRound(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull String worldName, @Nonnull NpcPlacementLedger ledger) {
        DespawnPass despawnPass = runDespawnPass(world, store, worldName);
        int rebound = runHealPass(world, store, worldName, ledger);
        PlacePass placePass = runPlacePass(world, store, worldName, ledger, despawnPass.standing());
        return new Round(new SweepSummary(despawnPass.scanned, despawnPass.despawned, rebound,
                placePass.placed(), placePass.unresolvedAnchors()), placePass.wokeASection());
    }

    /**
     * What one despawn pass did, and the instance keys ({@code placementId|anchorKey}) with a copy it kept
     * or adopted: the live half of the place pass's copy check ({@link #spawnsThisRound}).
     */
    private record DespawnPass(int scanned, int despawned, @Nonnull Set<String> standing) {
    }

    /**
     * One resident placed NPC by its instance and uuid: an adoption to record (the REBIND outcome), or a
     * kept copy (KEEP) for its upkeep. Package-private for the test.
     */
    record AdoptedRow(@Nonnull String placementId, @Nonnull String anchorKey, @Nonnull UUID uuid) {
    }

    /** The adoptions to record, and the copies beyond the first of one instance, to remove. */
    record AdoptionPlan(@Nonnull List<AdoptedRow> keep, @Nonnull List<AdoptedRow> surplus) {
    }

    /**
     * One adoption per placement instance. A wake can bring several parked copies of one instance back at
     * once (a build that spawned into sleeping sections parked one on every pass, and the section saved
     * them all): each reads "no row" in the same walk, and recording them all would leave every copy
     * standing until a later sweep found the row naming only the last. The first in walk order is
     * adopted; the rest are surplus. Package-private for the test.
     */
    @Nonnull
    static AdoptionPlan planAdoptions(@Nonnull List<AdoptedRow> adopted) {
        Map<String, AdoptedRow> firstPerInstance = new LinkedHashMap<>();
        List<AdoptedRow> surplus = new ArrayList<>();
        for (AdoptedRow row : adopted) {
            if (firstPerInstance.putIfAbsent(row.placementId() + '|' + row.anchorKey(), row) != null) {
                surplus.add(row);
            }
        }
        return new AdoptionPlan(List.copyOf(firstPerInstance.values()), List.copyOf(surplus));
    }

    /**
     * The copies the despawn pass keeps, for their upkeep: every KEEP, then the one adoption per instance;
     * never a surplus copy, which is removed. Package-private for the test.
     */
    @Nonnull
    static List<AdoptedRow> upkeepRows(@Nonnull List<AdoptedRow> kept, @Nonnull AdoptionPlan plan) {
        List<AdoptedRow> rows = new ArrayList<>(kept);
        rows.addAll(plan.keep());
        return List.copyOf(rows);
    }

    /**
     * Whether a place decision spawns this round (M123: wake, then spawn, idempotently): only a PLACE or a
     * REPLACE, and only when no copy of the instance stands live ({@code standing}: the instance keys this
     * round's despawn pass kept or adopted) or is held in the anchor's chunk section ({@code heldInSection},
     * read only when needed: the parked or saved copies a wake brings back as loads). A ticking section
     * normally holds none, since a wake re-adds them. The check guards beside the round rule, not instead
     * of it: a copy a wake brings back inside this same place pass is in neither set, and the anchor
     * section's WAIT step holds it back. Package-private for the test.
     */
    static boolean spawnsThisRound(@Nonnull PlaceDecision decision, @Nonnull String instanceKey,
            @Nonnull Set<String> standing, @Nonnull Supplier<Set<String>> heldInSection) {
        if (decision == PlaceDecision.SKIP || standing.contains(instanceKey)) {
            return false;
        }
        return !heldInSection.get().contains(instanceKey);
    }

    /**
     * Pass 1, component-authoritative: remove every standing placed NPC that should not be here,
     * and adopt (mint a ledger row for) one that is standing correctly but has none yet. Runs over
     * the store's own parallel iteration and removes through its command buffer, the first-party
     * pattern for a bulk removal.
     */
    @Nonnull
    private static DespawnPass runDespawnPass(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull String worldName) {
        ComponentType<EntityStore, PlacedNpcComponent> type = PlacedNpcComponent.getComponentType();
        if (type == null) {
            return new DespawnPass(0, 0, Set.of());
        }
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        NpcPlacementConfig config = NpcPlacementConfig.getInstance();
        NpcPlacementLedger ledger = NpcPlacementLedger.getInstance();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger despawned = new AtomicInteger();
        ConcurrentLinkedQueue<PlacedNpcIdentity> removedInstances = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<AdoptedRow> adopted = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<AdoptedRow> kept = new ConcurrentLinkedQueue<>();
        Set<String> standing = ConcurrentHashMap.newKeySet();

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
                    // The gate and the world match are folded into one pre-resolved boolean, since
                    // both inputs carry it; the pure core still distinguishes "unknown".
                    boolean instanceStillValid = known && allowed;

                    // Identity, not existence: TWO stamped entities can share one (placement,
                    // anchor) after an earlier REPLACE, and a mere hasRow() would call both of
                    // them a "match" forever, which is exactly how a duplicate used to stand
                    // unnoticed until this sweep. Only the entity the row's own uuid names is a
                    // match; the other one still carries an existing row, just not its own.
                    UUIDComponent uuidComponent = uuidType == null ? null : chunk.getComponent(index, uuidType);
                    UUID entityUuid = uuidComponent == null ? null : uuidComponent.getUuid();
                    UUID rowUuid = ledger.uuidOf(worldName, identity.placementId(), identity.anchorKey());
                    boolean rowExists = rowUuid != null;
                    boolean rowMatchesThisEntity = rowExists && entityUuid != null && rowUuid.equals(entityUuid);

                    ResidentDecision decision = decideResident(new ResidentInputs(
                            known, instanceStillValid, instanceStillValid, rowExists, rowMatchesThisEntity));
                    if (decision == ResidentDecision.DESPAWN) {
                        cmdBuffer.tryRemoveEntity(chunk.getReferenceTo(index), RemoveReason.REMOVE);
                        despawned.incrementAndGet();
                        if (!instanceStillValid) {
                            // The whole instance is gone (placement removed, gate now denies, or
                            // Where no longer matches): drop its ledger row, pin and cached
                            // position too.
                            removedInstances.add(identity);
                        }
                        // else: a surplus duplicate left over from an earlier REPLACE. The ledger
                        // row under this same (placement, anchor) key already names the OTHER,
                        // surviving entity - releasing it here would erase the correct row (and
                        // the pin/cached position it and the surviving entity share), and the
                        // place pass would read a ledger miss and spawn a THIRD one right after.
                    } else {
                        // KEEP or REBIND: a copy of this instance stands, so the place pass adds none
                        // (spawnsThisRound), and it gets its upkeep after the walk.
                        standing.add(NpcPlacementService.instanceKey(identity.placementId(), identity.anchorKey()));
                        if (entityUuid != null) {
                            AdoptedRow row = new AdoptedRow(identity.placementId(), identity.anchorKey(), entityUuid);
                            if (decision == ResidentDecision.REBIND) {
                                adopted.add(row);
                            } else {
                                kept.add(row);
                            }
                        }
                    }
                } catch (Throwable perEntity) {
                    SafeLog.fine("[placement] despawn pass, per-entity failure: " + perEntity.getMessage());
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[placement] despawn pass failed: " + t.getMessage());
        }

        // Bookkeeping happens OUTSIDE the iteration, same reason as the despawn drops below: a
        // ledger write is file I/O, which does not belong inside a parallel entity walk. Adopting
        // BEFORE the place pass runs (later in this same sweep) is load-bearing: it is what stops
        // the place pass reading a ledger miss for an instance that is, in fact, already standing.
        // One adoption per instance (planAdoptions): the extra copies a wake brought back go now.
        AdoptionPlan plan = planAdoptions(new ArrayList<>(adopted));
        for (AdoptedRow row : plan.keep()) {
            ledger.record(worldName, row.placementId(), row.anchorKey(), row.uuid());
        }
        for (AdoptedRow row : plan.surplus()) {
            if (NpcPlacementService.removeByUuid(store, row.uuid())) {
                despawned.incrementAndGet();
            }
        }

        // Upkeep for every copy this pass keeps (each KEEP, and the one adoption per instance; never a
        // surplus copy): a copy back from a park, or met after a restart, never ran place's bookkeeping,
        // so it gets its cached position, its keep-alive pin and its Fortify here. World thread, in the
        // sweep's world task, outside the walk; each step is idempotent.
        for (AdoptedRow row : upkeepRows(new ArrayList<>(kept), plan)) {
            NpcPlacementService.upkeep(world, store, worldName, config.resolve(row.placementId()),
                    row.placementId(), row.anchorKey(), row.uuid());
        }

        // Bookkeeping happens OUTSIDE the iteration: dropping a ledger row writes a file, and the
        // pin release reads a chunk, neither of which belongs inside a parallel entity walk.
        for (PlacedNpcIdentity identity : removedInstances) {
            NpcPlacementService.releaseInstance(world, identity.placementId(), identity.anchorKey());
        }
        return new DespawnPass(scanned.get(), despawned.get(), Set.copyOf(standing));
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

    /**
     * What one place pass did: how many it placed, how many wanted a position or a ticking section it
     * could not have yet (the retry signal - see {@link #runSweepAndMaybeRetry}), and whether it woke an
     * anchor's section (the signal for {@link #settleRounds}' second round).
     */
    private record PlacePass(int placed, int unresolvedAnchors, boolean wokeASection) {
    }

    /**
     * Pass 3, ledger-authoritative: place what is missing, and only what is provably missing. A placement
     * goes in only where no copy of its instance stands live ({@code standing}, the despawn pass's) or is
     * held in the anchor's chunk section ({@link #spawnsThisRound}).
     */
    @Nonnull
    private static PlacePass runPlacePass(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull String worldName, @Nonnull NpcPlacementLedger ledger, @Nonnull Set<String> standing) {
        int placed = 0;
        int unresolvedAnchors = 0;
        AnchorSections sections = new AnchorSections();
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

                int already = ledger.countInWorld(worldName, placementId);
                List<AnchorPosition> positions = PlacementAnchors.resolve(world, store, placement);
                if (positions.isEmpty()) {
                    if (already == 0) {
                        // This placement is enabled and wants to stand here, but every anchor
                        // group it authored resolved to nothing - e.g. Anchor.WorldSpawn asking
                        // for a world spawn point the engine has not resolved yet (WorldConfig's
                        // spawn provider is set asynchronously during world init and is null
                        // until that settles). That is a startup race, not a content problem, so
                        // it is worth a later-tick retry rather than leaving this placement
                        // unplaced for the rest of the world's lifetime. A placement that already
                        // has at least one standing instance is NOT counted here - MaxPerWorld or
                        // a since-removed Custom anchor legitimately resolving fewer positions is
                        // not a race to retry.
                        unresolvedAnchors++;
                        PlacementDiag.once(world, "unresolved-empty|" + placementId,
                                "[placement] '" + placementId + "' in '" + worldName
                                        + "': every authored anchor group resolved ZERO positions"
                                        + " (a WorldSpawn provider not settled, a Structure anchor"
                                        + " with no marker sighted yet, an undiscovered Zone, or an"
                                        + " unregistered Custom provider) - retrying");
                    }
                    continue;
                }
                NpcPlacementAsset.Limits limits = placement.getLimits();
                int max = limits == null ? 0 : limits.effectiveMaxPerWorld();
                boolean respawn = placement.getLifecycle() != null
                        && placement.getLifecycle().effectiveRespawn();

                for (AnchorPosition position : positions) {
                    String anchorKey = position.anchorKey();
                    String flightKey = worldName + '|' + placementId + '|' + anchorKey;
                    boolean ledgerHit = ledger.hasRow(worldName, placementId, anchorKey);
                    boolean atCapacity = max > 0 && !ledgerHit && already >= max;
                    // Update 7: an NPC stays in the world only in a TICKING chunk section, and a section
                    // ticks on its own, whatever its column does (TickingSections). A section this round
                    // woke is not ready either: AnchorSections holds it to the next round.
                    SectionPos section = SectionPos.ofBlock(position.x(), position.y(), position.z());
                    AnchorSections.Step step = sections.stepFor(section,
                            TickingSections.stateAt(world, position.x(), position.y(), position.z()));
                    boolean sectionReady = step == AnchorSections.Step.READY;

                    PlaceDecision decision = decidePlace(new PlaceInputs(
                            true, true, ledgerHit, sectionReady,
                            ledgerHit && isResident(store, ledger.uuidOf(worldName, placementId, anchorKey)),
                            respawn, atCapacity, IN_FLIGHT.contains(flightKey)));
                    if (decision == PlaceDecision.SKIP) {
                        // atCapacity is deliberately excluded: this placement would skip even with the
                        // section ticking, so neither a retry nor a wake could change the answer. Counting
                        // it would keep the world retrying over a decision that is already final.
                        if (!ledgerHit && !sectionReady && !atCapacity) {
                            // The anchor resolved a position just fine (unlike the positions.isEmpty()
                            // case above); what is missing is a TICKING section there. Nothing has woken
                            // it: no player stands at a world spawn point while a server boots, and an
                            // anchor can name a spot no route passes. A section in memory is woken on the
                            // spot and placed into on the sweep's next round (settleRounds), whose despawn
                            // pass first adopts any NPC the wake brought back; one not in memory is asked
                            // for ticking (requestAnchorSection), and its landing sweeps again. A row that
                            // exists over a sleeping section (a placed NPC asleep) is NOT this case: that
                            // is the steady state the design deliberately never retries against.
                            unresolvedAnchors++;
                            String next = switch (step) {
                                case WAKE -> {
                                    if (TickingSections.ensureTicking(world, position.x(), position.y(),
                                            position.z())) {
                                        sections.woke(section);
                                        yield "woke it, placing on the sweep's next round";
                                    }
                                    yield "retrying";
                                }
                                case LOAD -> requestAnchorSection(world, worldName, section, position)
                                        ? "loading it" : "retrying";
                                case WAIT, READY -> "placing on the sweep's next round";
                            };
                            PlacementDiag.once(world, "unresolved-asleep|" + placementId + '|' + anchorKey,
                                    "[placement] '" + placementId + "' in '" + worldName
                                            + "': anchor " + anchorKey + " resolved ("
                                            + Math.round(position.x()) + ","
                                            + Math.round(position.y()) + ","
                                            + Math.round(position.z()) + ") but its chunk section is not"
                                            + " ticking - " + next);
                        }
                        continue;
                    }

                    // Idempotent beside the round rule (M123): a copy of this instance standing live (this
                    // round's despawn pass kept or adopted it) or held asleep in the anchor's section (it
                    // comes back as a load when the section wakes) means no spawn this round.
                    if (!spawnsThisRound(decision, NpcPlacementService.instanceKey(placementId, anchorKey),
                            standing, () -> NpcPlacementService.heldInstances(world, section))) {
                        PlacementDiag.once(world, "copy-stands|" + placementId + '|' + anchorKey,
                                "[placement] '" + placementId + "' in '" + worldName + "': a copy for anchor "
                                        + anchorKey + " already stands or is held in its chunk section"
                                        + " - not placing another");
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
        return new PlacePass(placed, unresolvedAnchors, sections.wokeAny());
    }

    // ==================== helpers ====================

    /**
     * Does {@code placement}'s {@code Where} match {@code world}? A null or empty {@code Where}
     * defaults to {@link #DEFAULT_WHERE} at THIS read site (the group itself carries no default,
     * because a rules table and a placement want different ones).
     */
    public static boolean matchesWorld(@Nonnull NpcPlacementAsset placement, @Nullable World world) {
        var where = placement.getWhere();
        if (where == null || where.isBlank()) {
            return DEFAULT_WHERE.match(world) != null;
        }
        return where.match(world) != null;
    }

    /**
     * The world an unauthored {@code Where} means: the ordinary persistent world players log into,
     * which a stock Hytale server names {@code default}. A server whose main world is named
     * something else authors that name into the placement's own {@code Where} through its owner
     * layer, the same way any other leaf is overridden.
     */
    public static final String DEFAULT_WORLD_NAME = "default";

    /** The {@code Where} an unauthored one stands in for: an exact match on {@code default}. */
    private static final WorldSelector DEFAULT_WHERE =
            WorldSelector.of(new String[]{DEFAULT_WORLD_NAME}, null, null);

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
        PUMPING.clear();
        RESWEEP.clear();
        RETRY_EXHAUSTED.clear();
        SECTION_REQUESTS.clear();
    }
}
