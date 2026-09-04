package com.ziggfreed.common.encounter.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.util.SafeLog;

/**
 * One RUN of an encounter: the library's own bookkeeping on the encounter entity, beside the
 * engine's {@code EncounterManager}, {@code EncounterMembers} and the rest.
 *
 * <p><b>Registered without a codec, on purpose.</b> The engine persists exactly one thing about an
 * encounter, its script id, and rebuilds the fight from the script's start state on every load; a
 * run persisted beside that would be a second state machine disagreeing with the first. So this
 * component is excluded from chunk saves by construction (the engine serializes only codec-backed
 * components), a reload mints a fresh run, and nothing here outlives the entity.
 *
 * <p><b>The subject is keyed by uuid, never by a bare reference.</b> An in-place role change on the
 * boss reissues its entity reference while its uuid survives, so every subject read re-resolves
 * through the engine's own target slot and this uuid is only ever the identity check.
 *
 * <p>Written on the encounter's own world thread; read by an admin listing from any thread through
 * a {@link EncounterRun} snapshot.
 */
public final class ZigEncounterRun implements Component<EntityStore> {

    /** The registered type; null when registration failed, and every reader guards on that. */
    @Nullable
    public static ComponentType<EntityStore, ZigEncounterRun> TYPE;

    // identity
    @Nonnull private UUID runId = UUID.randomUUID();
    @Nullable private UUID worldUuid;

    // what the spawn call asked for; carried across a re-arm on the same entity
    @Nullable private String ownerKey;
    @Nullable private String difficulty;
    private double healthMultiplier = 1.0;
    private boolean showMarker;
    @Nonnull private List<UUID> seedMembers = List.of();

    // the subject
    @Nullable private UUID subjectUuid;
    @Nullable private String subjectMobId;
    private long subjectBoundAtMs;

    // the run's story
    private final long startedAtMs = System.currentTimeMillis();
    private long engagedAtMs;
    @Nullable private String phase;
    private int phaseIndex;
    private int waves;
    private boolean concluded;
    private boolean defeated;
    private boolean ended;
    @Nullable private UUID lastHitter;

    // members
    private final Set<UUID> knownMembers = new LinkedHashSet<>();
    private final Map<UUID, String> memberNames = new LinkedHashMap<>();
    private final Set<UUID> deadMembers = new LinkedHashSet<>();
    private long emptySinceMs;

    // scaling
    private boolean scaleApplied;
    private double lastScaleFactor = 1.0;
    private int reconciledPhaseIndex;

    // discovery
    private boolean markerPlaced;
    private long markerMovedAtMs;

    public ZigEncounterRun() {
    }

    /** A run stamped by the spawner with what its caller asked for. */
    @Nonnull
    public static ZigEncounterRun forSpawn(@Nonnull SpawnOptions options) {
        ZigEncounterRun run = new ZigEncounterRun();
        run.ownerKey = options.ownerKey();
        run.difficulty = options.difficulty();
        run.healthMultiplier = options.healthMultiplier();
        run.showMarker = options.showMarker();
        run.seedMembers = List.copyOf(options.seedMembers());
        return run;
    }

    /**
     * Register the component type with the entity-store registry, codec-less. Called once at library
     * setup, before any world loads. Never throws: a failure logs and leaves {@link #TYPE} unset, and
     * every reader guards on that.
     */
    @Nullable
    public static ComponentType<EntityStore, ZigEncounterRun> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(ZigEncounterRun.class, ZigEncounterRun::new);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " could not register ZigEncounterRun", t);
            return null;
        }
    }

    // ==================== identity ====================

    @Nonnull
    public UUID runId() {
        return runId;
    }

    @Nullable
    public UUID worldUuid() {
        return worldUuid;
    }

    public void bindWorld(@Nullable UUID worldUuid) {
        if (this.worldUuid == null) {
            this.worldUuid = worldUuid;
        }
    }

    // ==================== the spawn call ====================

    @Nullable
    public String ownerKey() {
        return ownerKey;
    }

    @Nullable
    public String difficulty() {
        return difficulty;
    }

    public double healthMultiplier() {
        return healthMultiplier;
    }

    public boolean showMarker() {
        return showMarker;
    }

    @Nonnull
    public List<UUID> seedMembers() {
        return seedMembers;
    }

    // ==================== the subject ====================

    @Nullable
    public UUID subjectUuid() {
        return subjectUuid;
    }

    @Nullable
    public String subjectMobId() {
        return subjectMobId;
    }

    public boolean hasSubject() {
        return subjectUuid != null;
    }

    public long subjectBoundAtMs() {
        return subjectBoundAtMs;
    }

    /** Record the subject at its FIRST bind; a later re-resolve of the same uuid changes nothing. */
    public void bindSubject(@Nonnull UUID uuid, @Nullable String mobId, long nowMs) {
        if (subjectUuid == null) {
            subjectUuid = uuid;
            subjectMobId = mobId;
            subjectBoundAtMs = nowMs;
        }
    }

    // ==================== the story ====================

    public long startedAtMs() {
        return startedAtMs;
    }

    public long engagedAtMs() {
        return engagedAtMs;
    }

    public boolean isEngaged() {
        return engagedAtMs > 0L;
    }

    public void engage(long nowMs) {
        if (engagedAtMs == 0L) {
            engagedAtMs = nowMs;
        }
    }

    /** Milliseconds since the engage, or since the run started when it never engaged. */
    public long elapsedMs(long nowMs) {
        return Math.max(0L, nowMs - (isEngaged() ? engagedAtMs : startedAtMs));
    }

    @Nullable
    public String phase() {
        return phase;
    }

    public int phaseIndex() {
        return phaseIndex;
    }

    /** Enter {@code state}; answers the phase the run was in. */
    @Nullable
    public String enterPhase(@Nonnull String state) {
        String from = phase;
        phase = state;
        phaseIndex++;
        return from;
    }

    public int waves() {
        return waves;
    }

    public void countWave() {
        waves++;
    }

    public boolean isConcluded() {
        return concluded;
    }

    public boolean isDefeated() {
        return defeated;
    }

    /** Latch the conclusion; answers true only for the call that latched it. */
    public boolean conclude(boolean asDefeat) {
        if (concluded) {
            return false;
        }
        concluded = true;
        defeated = asDefeat;
        return true;
    }

    public boolean isEnded() {
        return ended;
    }

    public void end() {
        ended = true;
    }

    @Nullable
    public UUID lastHitter() {
        return lastHitter;
    }

    public void noteHitter(@Nullable UUID playerId) {
        if (playerId != null) {
            lastHitter = playerId;
        }
    }

    // ==================== members ====================

    /** Every player seen as a member during this run, in first-seen order. */
    @Nonnull
    public Set<UUID> knownMembers() {
        return Collections.unmodifiableSet(knownMembers);
    }

    @Nullable
    public String memberName(@Nonnull UUID playerId) {
        return memberNames.get(playerId);
    }

    public void noteMember(@Nonnull UUID playerId, @Nullable String name) {
        knownMembers.add(playerId);
        if (name != null && !name.isBlank()) {
            memberNames.put(playerId, name);
        }
    }

    @Nonnull
    public Set<UUID> deadMembers() {
        return Collections.unmodifiableSet(deadMembers);
    }

    public void noteDeath(@Nonnull UUID playerId) {
        knownMembers.add(playerId);
        deadMembers.add(playerId);
    }

    public int memberDeaths() {
        return deadMembers.size();
    }

    /** True when every member this run ever saw has died. */
    public boolean allKnownMembersDead() {
        return !knownMembers.isEmpty() && deadMembers.containsAll(knownMembers);
    }

    public long emptySinceMs() {
        return emptySinceMs;
    }

    public void markEmptySince(long nowMs) {
        if (emptySinceMs == 0L) {
            emptySinceMs = nowMs;
        }
    }

    public void markOccupied() {
        emptySinceMs = 0L;
    }

    // ==================== scaling ====================

    public boolean isScaleApplied() {
        return scaleApplied;
    }

    public double lastScaleFactor() {
        return lastScaleFactor;
    }

    public void noteScale(double factor) {
        scaleApplied = true;
        lastScaleFactor = factor;
        reconciledPhaseIndex = phaseIndex;
    }

    /** True when a phase beat has landed since the scale was last applied. */
    public boolean needsPhaseReconcile() {
        return scaleApplied && reconciledPhaseIndex != phaseIndex;
    }

    // ==================== discovery ====================

    public boolean isMarkerPlaced() {
        return markerPlaced;
    }

    public long markerMovedAtMs() {
        return markerMovedAtMs;
    }

    public void noteMarker(long nowMs) {
        markerPlaced = true;
        markerMovedAtMs = nowMs;
    }

    public void clearMarker() {
        markerPlaced = false;
        markerMovedAtMs = 0L;
    }

    // ==================== re-arm ====================

    /**
     * Start the next run on the same entity: a fresh id, the story cleared, the spawn call's own
     * options kept. Answers the new run id.
     */
    @Nonnull
    public UUID rollOver() {
        runId = UUID.randomUUID();
        subjectUuid = null;
        subjectMobId = null;
        subjectBoundAtMs = 0L;
        engagedAtMs = 0L;
        phase = null;
        phaseIndex = 0;
        waves = 0;
        concluded = false;
        defeated = false;
        lastHitter = null;
        knownMembers.clear();
        memberNames.clear();
        deadMembers.clear();
        emptySinceMs = 0L;
        scaleApplied = false;
        lastScaleFactor = 1.0;
        reconciledPhaseIndex = 0;
        markerPlaced = false;
        markerMovedAtMs = 0L;
        return runId;
    }

    /** A copied entity mints its own run; nothing of this one's story travels with the copy. */
    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        ZigEncounterRun copy = new ZigEncounterRun();
        copy.ownerKey = ownerKey;
        copy.difficulty = difficulty;
        copy.healthMultiplier = healthMultiplier;
        copy.showMarker = showMarker;
        copy.seedMembers = new ArrayList<>(seedMembers);
        return copy;
    }
}
