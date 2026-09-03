package com.ziggfreed.common.encounter.system;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.builtin.encountermanager.EncounterManagerSystems;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.event.ResetReason;
import com.ziggfreed.common.encounter.payout.EncounterDiscovery;
import com.ziggfreed.common.encounter.run.EncounterChunkHold;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterMembership;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.EncounterScaling;
import com.ziggfreed.common.encounter.run.EncounterSubjects;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.encounter.validate.EncounterScripts;
import com.ziggfreed.common.util.EntityIdentifierUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * Once per tick per encounter entity, AFTER the engine's own tick has run the script: re-resolve
 * the subject and the members and refresh the hot indexes, seed the party, credit presence, apply
 * or reconcile the health scale, hold the chunk ticking while the fight is open, watch for a wipe
 * or a timeout, and move the map marker. Nothing here decides the fight; it reads what the engine
 * decided.
 *
 * <p>Not parallel: members are player entities that may stand in two overlapping fights at once,
 * which is the same reason the engine's own member tick is serial.
 */
public final class EncounterTickSystem extends EntityTickingSystem<EntityStore> {

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, EncounterManagerSystems.TickSystem.class));

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return EncounterManager.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            EncounterManager manager = archetypeChunk.getComponent(index, EncounterManager.getComponentType());
            if (manager == null || !manager.isBuilt() || manager.getEncounterId() == null) {
                return;
            }
            ZigEncounterRun run = ZigEncounterRun.TYPE == null ? null
                    : archetypeChunk.getComponent(index, ZigEncounterRun.TYPE);
            if (run == null || run.isEnded()) {
                return;
            }
            String encounterId = manager.getEncounterId();
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
            if (row != null && !row.isEnabled()) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            long now = System.currentTimeMillis();
            tickOne(store, ref, run, encounterId, row, dt, now);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " tick failed", t);
        }
    }

    private static void tickOne(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, @Nullable EncounterBindingAsset row,
            float dt, long now) {
        UUID worldUuid = EncounterLifecycle.worldUuid(store);
        run.bindWorld(worldUuid);
        if (!EncounterRuns.isTracked(run.runId()) && worldUuid != null) {
            EncounterRuns.track(run, worldUuid, encounterId, ref);
        }

        // The subject, re-resolved every tick through the script's own slot.
        Ref<EntityStore> subject = EncounterSubjects.resolve(store, ref, row == null ? null : row.getSubject(),
                row != null);
        if (subject != null && !run.hasSubject()) {
            UUID subjectUuid = EncounterSubjects.uuidOf(store, subject);
            if (subjectUuid != null) {
                String mobId = EntityIdentifierUtil.getMobId(store, subject);
                run.bindSubject(subjectUuid, mobId, now);
                SafeLog.info(Encounters.LOG_PREFIX + " subject bound run=" + EncounterRun.shortId(run.runId())
                        + " encounter=" + encounterId + " mob=" + (mobId == null ? "?" : mobId));
            }
        }
        EncounterRuns.indexSubject(run.runId(), subject);

        // The members: the engine's roster, plus whoever the spawn call seeded.
        EncounterMembership.stampSeeded(store, ref, run);
        List<Ref<EntityStore>> memberRefs = EncounterRuns.memberRefs(store, ref);
        int alive = 0;
        boolean counting = run.isEngaged() && !run.isConcluded();
        for (Ref<EntityStore> member : memberRefs) {
            PlayerRef player = store.getComponent(member, PlayerRef.getComponentType());
            if (player == null || player.getUuid() == null) {
                continue;
            }
            run.noteMember(player.getUuid(), player.getUsername());
            if (store.getComponent(member, DeathComponent.getComponentType()) == null) {
                alive++;
            }
            if (counting) {
                EncounterRuns.LEDGER.creditPresence(run.runId(), player.getUuid(), player.getUsername(), dt);
            }
        }
        EncounterRuns.indexMembers(run.runId(), memberRefs);

        // An engage the script never announces: the subject bound and the grace ran out.
        if (!run.isEngaged() && !run.isConcluded() && run.hasSubject()
                && !EncounterScripts.authorsEngaged(encounterId)) {
            int grace = row == null || row.getTiming() == null
                    ? EncounterBindingAsset.Timing.DEFAULT_ENGAGE_GRACE_SECONDS : row.getTiming().engageGraceSeconds();
            if (now - run.subjectBoundAtMs() >= Math.max(0, grace) * 1000L) {
                EncounterLifecycle.engage(store, ref, run, encounterId, "grace");
            }
        }

        // The health scale: once at the bind, again after each phase.
        if (subject != null && !run.isConcluded()) {
            scale(store, subject, run, row, memberRefs);
        }

        // A wipe, or a run that outlived its budget. While the fight is open its chunk is held
        // ticking, so both guards are measured by this tick and never cut short by a cold chunk.
        if (run.isEngaged() && !run.isConcluded()) {
            EncounterChunkHold.holdTicking(store, ref);
            EncounterBindingAsset.Timing timing = row == null ? null : row.getTiming();
            int wipeGrace = timing == null ? EncounterBindingAsset.Timing.DEFAULT_WIPE_GRACE_SECONDS : timing.wipeGraceSeconds();
            int maxRun = timing == null ? EncounterBindingAsset.Timing.DEFAULT_MAX_RUN_SECONDS : timing.maxRunSeconds();
            if (alive == 0) {
                run.markEmptySince(now);
                if (now - run.emptySinceMs() >= Math.max(0, wipeGrace) * 1000L) {
                    EncounterLifecycle.wipe(store, ref, run, encounterId, run.allKnownMembersDead());
                }
            } else {
                run.markOccupied();
            }
            if (!run.isConcluded() && maxRun > 0 && run.elapsedMs(now) >= maxRun * 1000L) {
                EncounterLifecycle.reset(store, ref, run, encounterId, ResetReason.TIMEOUT, true);
                return;
            }
        }

        EncounterDiscovery.follow(store, ref, run, row, now);
    }

    private static void scale(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> subject,
            @Nonnull ZigEncounterRun run, @Nullable EncounterBindingAsset row, @Nonnull List<Ref<EntityStore>> memberRefs) {
        EncounterBindingAsset.Scale spec = row == null ? null : row.getScale();
        boolean first = !run.isScaleApplied();
        boolean reconcile = !first && run.needsPhaseReconcile() && (spec == null || spec.reconcileOnPhase());
        if (!first && !reconcile) {
            return;
        }
        if (!statsReady(store, subject)) {
            return; // balancing has not run on the subject yet; try again next tick
        }
        double perPower = spec == null ? EncounterBindingAsset.Scale.DEFAULT_HEALTH_PER_POWER_POINT
                : spec.healthPerPowerPoint();
        double power = perPower != 0.0 ? EncounterSeams.aggregatedPower(store, subject, memberRefs) : 0.0;
        int members = Math.max(memberRefs.size(), run.seedMembers().size());
        double factor = EncounterScaling.factor(spec, members, power, run.healthMultiplier());
        boolean changed = EncounterScaling.apply(store, subject, factor, first);
        run.noteScale(factor);
        if (changed || first) {
            SafeLog.info(Encounters.LOG_PREFIX + " scale run=" + EncounterRun.shortId(run.runId()) + " factor="
                    + Math.round(factor * 100.0) / 100.0 + " members=" + members
                    + (power > 0.0 ? " power=" + Math.round(power) : "") + (first ? " (applied)" : " (reconciled)"));
        }
    }

    private static boolean statsReady(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> subject) {
        try {
            EntityStatMap stats = store.getComponent(subject, EntityStatsModule.get().getEntityStatMapComponentType());
            return stats != null && stats.get(DefaultEntityStatTypes.getHealth()) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
