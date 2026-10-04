package com.ziggfreed.common.encounter.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.builtin.encountermanager.EncounterMembers;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.ledger.ParticipationLedger;

/**
 * Where the runtime keeps what it has to find FAST, off the world thread's hot paths: the one
 * participation ledger, the table of live runs, two reference-keyed indexes the damage system
 * consults on every hit, and the adds a run's spawners raised that its tick has yet to scale.
 *
 * <p><b>The indexes are rebuilt by the tick, never trusted across it.</b> A bound subject's
 * reference and a member's reference are both liable to change under the run (an in-place role
 * change reissues the boss's, a reconnect reissues a player's), so each tick re-resolves what it
 * has and re-puts it here; a hit between two ticks resolves through whatever the last tick knew,
 * which is at most one tick stale. A miss is one hash lookup on a map that is empty whenever no
 * fight is on. The lineage index works the same way: each tick points the encounter entity's native
 * {@code SpawnLineage} id at its current run, so a spawn the engine stamps with that lineage is known
 * as the run's add.
 */
public final class EncounterRuns {

    /** The one ledger, keyed by (run, player). */
    public static final ParticipationLedger LEDGER = new ParticipationLedger();

    /** The most adds one run holds waiting for its tick; past it a new one is let go unscaled. */
    public static final int MAX_PENDING_ADDS = 256;

    /** A live run, by id: the world it is in, its script, and the encounter entity carrying it. */
    public record Live(@Nonnull UUID worldUuid, @Nonnull String encounterId, @Nonnull Ref<EntityStore> encounterRef,
                       @Nonnull ZigEncounterRun run) {
    }

    private static final Map<UUID, Live> LIVE = new ConcurrentHashMap<>();
    private static final Map<Ref<EntityStore>, UUID> SUBJECT_INDEX = new ConcurrentHashMap<>();
    private static final Map<Ref<EntityStore>, UUID> MEMBER_INDEX = new ConcurrentHashMap<>();
    private static final Map<String, UUID> LINEAGE_INDEX = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Ref<EntityStore>, Integer>> PENDING_ADDS = new ConcurrentHashMap<>();

    private EncounterRuns() {
    }

    // ==================== reading the entity ====================

    /** The run on {@code encounterRef}, or null when it carries none (or the type failed to register). */
    @Nullable
    public static ZigEncounterRun runOn(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> encounterRef) {
        if (ZigEncounterRun.TYPE == null || encounterRef == null || !encounterRef.isValid()) {
            return null;
        }
        return accessor.getComponent(encounterRef, ZigEncounterRun.TYPE);
    }

    /** The native script id on {@code encounterRef}, or null when it is not an encounter entity. */
    @Nullable
    public static String encounterIdOn(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> encounterRef) {
        if (encounterRef == null || !encounterRef.isValid()) {
            return null;
        }
        EncounterManager manager = accessor.getComponent(encounterRef, EncounterManager.getComponentType());
        return manager == null ? null : manager.getEncounterId();
    }

    /** The live member refs on {@code encounterRef}: the engine's own TTL-stamped roster, valid entries only. */
    @Nonnull
    public static List<Ref<EntityStore>> memberRefs(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> encounterRef) {
        if (encounterRef == null || !encounterRef.isValid()) {
            return List.of();
        }
        EncounterMembers members = accessor.getComponent(encounterRef, EncounterMembers.getComponentType());
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Ref<EntityStore>> out = new ArrayList<>(members.getMemberTtl().size());
        for (Ref<EntityStore> ref : members.getMemberTtl().keySet()) {
            if (ref != null && ref.isValid()) {
                out.add(ref);
            }
        }
        return out;
    }

    /** The member uuids on {@code encounterRef}, in roster order. */
    @Nonnull
    public static List<UUID> memberIds(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> encounterRef) {
        List<UUID> out = new ArrayList<>();
        for (Ref<EntityStore> ref : memberRefs(accessor, encounterRef)) {
            PlayerRef player = accessor.getComponent(ref, PlayerRef.getComponentType());
            if (player != null && player.getUuid() != null) {
                out.add(player.getUuid());
            }
        }
        return out;
    }

    // ==================== the live table ====================

    public static void track(@Nonnull ZigEncounterRun run, @Nonnull UUID worldUuid, @Nonnull String encounterId,
            @Nonnull Ref<EntityStore> encounterRef) {
        LIVE.put(run.runId(), new Live(worldUuid, encounterId, encounterRef, run));
    }

    public static boolean isTracked(@Nonnull UUID runId) {
        return LIVE.containsKey(runId);
    }

    public static void untrack(@Nonnull UUID runId) {
        LIVE.remove(runId);
        SUBJECT_INDEX.values().removeIf(runId::equals);
        MEMBER_INDEX.values().removeIf(runId::equals);
        LINEAGE_INDEX.values().removeIf(runId::equals);
        PENDING_ADDS.remove(runId);
    }

    @Nullable
    public static Live live(@Nonnull UUID runId) {
        return LIVE.get(runId);
    }

    /** Every live run, in no particular order; a snapshot. */
    @Nonnull
    public static List<Live> allLive() {
        return new ArrayList<>(LIVE.values());
    }

    // ==================== the hot indexes ====================

    /** Point {@code subjectRef} at {@code runId}, dropping whatever reference the run pointed at before. */
    public static void indexSubject(@Nonnull UUID runId, @Nullable Ref<EntityStore> subjectRef) {
        SUBJECT_INDEX.values().removeIf(runId::equals);
        if (subjectRef != null && subjectRef.isValid()) {
            SUBJECT_INDEX.put(subjectRef, runId);
        }
    }

    /** The run {@code ref} is the bound subject of, or null: the damage system's miss path. */
    @Nullable
    public static UUID runOfSubject(@Nonnull Ref<EntityStore> ref) {
        return SUBJECT_INDEX.isEmpty() ? null : SUBJECT_INDEX.get(ref);
    }

    /** Replace the member index of {@code runId} with {@code memberRefs}. */
    public static void indexMembers(@Nonnull UUID runId, @Nonnull List<Ref<EntityStore>> memberRefs) {
        MEMBER_INDEX.values().removeIf(runId::equals);
        for (Ref<EntityStore> ref : memberRefs) {
            MEMBER_INDEX.put(ref, runId);
        }
    }

    /** The run {@code ref} is currently a member of, or null: the damage system's miss path. */
    @Nullable
    public static UUID runOfMember(@Nonnull Ref<EntityStore> ref) {
        return MEMBER_INDEX.isEmpty() ? null : MEMBER_INDEX.get(ref);
    }

    /** True when {@code ref} is the bound subject of any live run. */
    public static boolean isBoundSubject(@Nonnull Ref<EntityStore> ref) {
        return runOfSubject(ref) != null;
    }

    // ==================== the adds ====================

    /**
     * Point {@code lineageId} (the native {@code SpawnLineage} the engine stamps on the encounter entity
     * and copies onto everything its spawners raise) at {@code runId}, dropping whatever the run pointed
     * at before.
     */
    public static void indexLineage(@Nonnull UUID runId, @Nullable String lineageId) {
        LINEAGE_INDEX.values().removeIf(runId::equals);
        if (lineageId != null && !lineageId.isBlank()) {
            LINEAGE_INDEX.put(lineageId, runId);
        }
    }

    /** The live run whose encounter carries {@code lineageId}, or null: the add system's lookup. */
    @Nullable
    public static UUID runOfLineage(@Nullable String lineageId) {
        return lineageId == null || LINEAGE_INDEX.isEmpty() ? null : LINEAGE_INDEX.get(lineageId);
    }

    /**
     * Hold an add the run's encounter just raised until the run's next tick. Noting one already held
     * changes nothing.
     *
     * @return false only when the run already holds {@link #MAX_PENDING_ADDS} and this add is new
     */
    public static boolean notePendingAdd(@Nonnull UUID runId, @Nonnull Ref<EntityStore> addRef) {
        Map<Ref<EntityStore>, Integer> waiting = PENDING_ADDS.computeIfAbsent(runId, k -> new ConcurrentHashMap<>());
        if (waiting.containsKey(addRef)) {
            return true;
        }
        if (waiting.size() >= MAX_PENDING_ADDS) {
            return false;
        }
        waiting.put(addRef, 0);
        return true;
    }

    /** Every add waiting on {@code runId}, each with the ticks it has waited; the run forgets them. */
    @Nonnull
    public static Map<Ref<EntityStore>, Integer> takePendingAdds(@Nonnull UUID runId) {
        Map<Ref<EntityStore>, Integer> taken = PENDING_ADDS.remove(runId);
        return taken == null ? Map.of() : taken;
    }

    /** Put an add back to wait one more tick, remembering how long it has waited. */
    public static void requeuePendingAdd(@Nonnull UUID runId, @Nonnull Ref<EntityStore> addRef, int waited) {
        PENDING_ADDS.computeIfAbsent(runId, k -> new ConcurrentHashMap<>()).put(addRef, waited);
    }

    /** Drop everything; for a test starting from nothing. */
    public static void resetForTests() {
        LIVE.clear();
        SUBJECT_INDEX.clear();
        MEMBER_INDEX.clear();
        LINEAGE_INDEX.clear();
        PENDING_ADDS.clear();
    }
}
