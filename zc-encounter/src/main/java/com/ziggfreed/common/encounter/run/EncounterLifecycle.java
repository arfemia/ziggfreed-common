package com.ziggfreed.common.encounter.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.asset.EncounterParticipationAsset;
import com.ziggfreed.common.encounter.asset.EncounterParticipationConfig;
import com.ziggfreed.common.encounter.asset.ParticipationRules;
import com.ziggfreed.common.encounter.asset.ParticipationSpec;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.event.EncounterEngagedEvent;
import com.ziggfreed.common.encounter.event.EncounterPhaseChangedEvent;
import com.ziggfreed.common.encounter.event.EncounterResetEvent;
import com.ziggfreed.common.encounter.event.EncounterSignalEvent;
import com.ziggfreed.common.encounter.event.EncounterWipedEvent;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.event.ResetReason;
import com.ziggfreed.common.encounter.ledger.ParticipationShares;
import com.ziggfreed.common.encounter.payout.EncounterDiscovery;
import com.ziggfreed.common.encounter.payout.EncounterFeedback;
import com.ziggfreed.common.encounter.payout.EncounterLoot;
import com.ziggfreed.common.encounter.signal.EncounterSignal;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.util.SafeLog;

/**
 * The story of a run, told in order: engaged, then phases and waves, then defeated or wiped, then
 * reset. Every beat here settles the run's own state, writes ONE line under {@code [encounter]},
 * fires the native event, and hands the payout layer whatever the binding row owes. Nothing here
 * decides when a beat happens; the script (through the signal system), the death system and the
 * tick do, and call in.
 *
 * <p>World thread only; every method is guarded so a failing listener or payout never takes the
 * fight down.
 */
public final class EncounterLifecycle {

    private EncounterLifecycle() {
    }

    // ==================== engaged ====================

    /** The fight is on. Idempotent: a second call on an engaged run does nothing. */
    public static void engage(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, @Nonnull String how) {
        if (run.isEngaged()) {
            return;
        }
        long now = System.currentTimeMillis();
        run.engage(now);
        run.bindWorld(worldUuid(store));
        EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
        List<UUID> members = EncounterRuns.memberIds(store, encounterRef);
        String nameKey = row == null ? null : row.getNameKey();
        String difficulty = difficultyOf(run, row);
        SafeLog.info(Encounters.LOG_PREFIX + " engaged run=" + EncounterRun.shortId(run.runId()) + " encounter="
                + encounterId + " members=" + members.size() + " subject="
                + (run.subjectMobId() == null ? "none" : run.subjectMobId()) + " by=" + how);
        Encounters.fireEngaged(() -> new EncounterEngagedEvent(run.runId(), encounterId, nameKey, run.worldUuid(),
                run.subjectUuid(), run.subjectMobId(), members, now, difficulty));
        EncounterFeedback.fire(store, feedbackId(row, EncounterBindingAsset.Feedback::engaged,
                EncounterBindingAsset.DEFAULT_ENGAGED_MOMENT), members, baseArgs(run, encounterId, row, members.size(), now));
        EncounterDiscovery.onEngaged(store, encounterRef, run, row, now);
    }

    // ==================== phases and beats ====================

    /** The script entered {@code state}. */
    public static void phase(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, @Nonnull String state) {
        long now = System.currentTimeMillis();
        String from = run.enterPhase(state);
        EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
        List<UUID> members = EncounterRuns.memberIds(store, encounterRef);
        long elapsed = run.elapsedMs(now);
        SafeLog.info(Encounters.LOG_PREFIX + " phase run=" + EncounterRun.shortId(run.runId()) + " encounter="
                + encounterId + " " + (from == null ? "start" : from) + " -> " + state + " (#" + run.phaseIndex()
                + ", " + elapsed / 1000L + "s)");
        Encounters.firePhaseChanged(() -> new EncounterPhaseChangedEvent(run.runId(), encounterId, from, state,
                run.phaseIndex(), members, elapsed));
        Map<String, Object> args = baseArgs(run, encounterId, row, members.size(), now);
        args.put(EncounterFeedback.PHASE_ARG, state);
        EncounterFeedback.fire(store, feedbackId(row, EncounterBindingAsset.Feedback::phaseChanged,
                EncounterBindingAsset.DEFAULT_PHASE_MOMENT), members, args);
        EncounterLoot.dropPhase(store, encounterRef, run, row, state);
    }

    /** A wave beat, or the author's own beat: counted where it is a wave, announced either way. */
    public static void signal(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, @Nonnull EncounterSignal signal) {
        if (signal.moment() == EncounterSignal.Moment.WAVE) {
            run.countWave();
        }
        int members = EncounterRuns.memberIds(store, encounterRef).size();
        String suffix = signal.moment() == EncounterSignal.Moment.CUSTOM ? signal.detail()
                : (signal.detail() == null ? signal.moment().word() : signal.moment().word() + ":" + signal.detail());
        SafeLog.info(Encounters.LOG_PREFIX + " signal run=" + EncounterRun.shortId(run.runId()) + " encounter="
                + encounterId + " id=" + signal.raw() + (signal.moment() == EncounterSignal.Moment.WAVE
                ? " (wave #" + run.waves() + ")" : ""));
        UUID worldUuid = run.worldUuid() != null ? run.worldUuid() : worldUuid(store);
        Encounters.fireSignal(() -> new EncounterSignalEvent(run.runId(), encounterId, signal.raw(), suffix, worldUuid,
                members, encounterRef));
    }

    // ==================== defeated ====================

    /**
     * The subject is down. Latched: only the first call settles the run; the rest do nothing. The
     * rest the row owes ({@code Timing.Rest}) is stamped on the encounter entity through
     * {@code commandBuffer}, since a defeat is decided inside a system.
     *
     * @param commandBuffer the calling system's buffer, which the rest stamp lands through
     * @param subjectRef    the subject's live reference when the death system has it, else null
     * @param how           what decided it, for the log line
     */
    public static void defeat(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> encounterRef, @Nonnull ZigEncounterRun run, @Nonnull String encounterId,
            @Nullable Ref<EntityStore> subjectRef, @Nonnull String how) {
        if (!run.conclude(true)) {
            return;
        }
        long now = System.currentTimeMillis();
        run.bindWorld(worldUuid(store));
        EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
        ParticipationSpec spec = specFor(store, run, row);
        ParticipationShares shares = settle(store, run, spec);
        double elapsedSeconds = run.elapsedMs(now) / 1000.0;
        List<UUID> memberIds = EncounterRuns.memberIds(store, encounterRef);
        SafeLog.info(Encounters.LOG_PREFIX + " defeated run=" + EncounterRun.shortId(run.runId()) + " encounter="
                + encounterId + " after " + Math.round(elapsedSeconds) + "s, participants=" + shares.size()
                + " credited=" + shares.credited().size() + " deaths=" + run.memberDeaths() + " by=" + how);
        String difficulty = difficultyOf(run, row);
        Encounters.fireDefeated(() -> new EncounterDefeatedEvent(run.runId(), encounterId, run.worldUuid(),
                run.subjectUuid(), run.subjectMobId(), shares.participants(), shares.participantIds(),
                shares.shares(), shares.damageDealt(), elapsedSeconds, run.memberDeaths(), difficulty,
                run.lastHitter()));
        EncounterRest.stamp(store, commandBuffer, encounterRef, run, encounterId, row);
        EncounterLoot.grantDefeat(store, run, encounterId, row, spec, shares);
        Map<String, Object> args = baseArgs(run, encounterId, row, memberIds.size(), now);
        args.put(EncounterFeedback.SECONDS_ARG, Math.round(elapsedSeconds));
        EncounterFeedback.fireWithShares(store, feedbackId(row, EncounterBindingAsset.Feedback::defeated,
                EncounterBindingAsset.DEFAULT_DEFEATED_MOMENT), shares.participants(), args);
    }

    // ==================== wiped ====================

    /**
     * The fight was lost. Latched like a defeat.
     *
     * @param allMembersDead true when every member the run saw had died; false when they left
     */
    public static void wipe(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, boolean allMembersDead) {
        if (!run.conclude(false)) {
            return;
        }
        long now = System.currentTimeMillis();
        run.bindWorld(worldUuid(store));
        EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(encounterId);
        ParticipationSpec spec = specFor(store, run, row);
        ParticipationShares shares = settle(store, run, spec);
        double elapsedSeconds = run.elapsedMs(now) / 1000.0;
        double health = subjectHealthFraction(store, encounterRef, run, row);
        SafeLog.info(Encounters.LOG_PREFIX + " wiped run=" + EncounterRun.shortId(run.runId()) + " encounter="
                + encounterId + " after " + Math.round(elapsedSeconds) + "s, participants=" + shares.size()
                + " deaths=" + run.memberDeaths() + (allMembersDead ? " (everyone died)" : " (everyone left)"));
        Encounters.fireWiped(() -> new EncounterWipedEvent(run.runId(), encounterId, run.worldUuid(),
                shares.participants(), shares.participantIds(), shares.shares(), elapsedSeconds, run.memberDeaths(),
                allMembersDead, run.phase(), health));
        Map<String, Object> args = baseArgs(run, encounterId, row, run.knownMembers().size(), now);
        args.put(EncounterFeedback.SECONDS_ARG, Math.round(elapsedSeconds));
        EncounterFeedback.fireWithShares(store, feedbackId(row, EncounterBindingAsset.Feedback::wiped,
                EncounterBindingAsset.DEFAULT_WIPED_MOMENT), shares.participants(), args);
    }

    // ==================== reset ====================

    /**
     * The run is over. A run that engaged and never concluded is settled as a wipe first, so Reset
     * stays the last event of every run. When {@code continues}, the same entity carries on under a
     * fresh run id; otherwise the run simply ends with it.
     */
    public static void reset(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nonnull String encounterId, @Nonnull ResetReason reason,
            boolean continues) {
        if (run.isEnded()) {
            return;
        }
        if (run.isEngaged() && !run.isConcluded()) {
            wipe(store, encounterRef, run, encounterId, run.allKnownMembersDead());
        }
        UUID endedId = run.runId();
        EncounterDiscovery.onReset(store, run);
        EncounterRuns.LEDGER.drop(endedId);
        EncounterRuns.untrack(endedId);
        UUID nextId = continues ? run.rollOver() : null;
        if (!continues) {
            run.end();
        }
        SafeLog.info(Encounters.LOG_PREFIX + " reset run=" + EncounterRun.shortId(endedId) + " encounter="
                + encounterId + " reason=" + reason + (nextId == null ? "" : " next=" + EncounterRun.shortId(nextId)));
        Encounters.fireReset(() -> new EncounterResetEvent(endedId, encounterId, nextId, reason));
    }

    // ==================== shared readings ====================

    /** The credit rules for {@code run}: the matched rule under the row's own override. */
    @Nonnull
    public static ParticipationSpec specFor(@Nonnull Store<EntityStore> store, @Nonnull ZigEncounterRun run,
            @Nullable EncounterBindingAsset row) {
        World world = worldOf(store);
        String worldName = world == null ? null : world.getName();
        String gameplay = world == null ? null : world.getWorldConfig().getGameplayConfig();
        EncounterParticipationAsset rule = ParticipationRules.resolve(run.subjectMobId(), worldName, gameplay,
                EncounterParticipationConfig.getInstance().all().values());
        return ParticipationSpec.of(rule, row == null ? null : row.getParticipation());
    }

    /** Everybody's standing in {@code run}, each participant's weights read about them where they stand. */
    @Nonnull
    public static ParticipationShares settle(@Nonnull Store<EntityStore> store, @Nonnull ZigEncounterRun run,
            @Nonnull ParticipationSpec spec) {
        return EncounterRuns.LEDGER.shares(run.runId(), spec.minShare(), spec.creditDead(), playerId -> {
            PlayerRef player = Universe.get().getPlayer(playerId);
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            boolean here = ref != null && ref.isValid() && ref.getStore() == store;
            return spec.weightsFor(EncounterFactors.lookupAbout(here ? store : null, here ? ref : null));
        });
    }

    /** The run's difficulty label: the spawn call's, else the row's, else null. */
    @Nullable
    public static String difficultyOf(@Nonnull ZigEncounterRun run, @Nullable EncounterBindingAsset row) {
        if (run.difficulty() != null && !run.difficulty().isBlank()) {
            return run.difficulty();
        }
        return row == null || row.getProgression() == null ? null : row.getProgression().getDifficulty();
    }

    /** The fight's display name, resolved on each reader's own client: the row's key, else the script id. */
    @Nonnull
    public static Message titleOf(@Nonnull String encounterId, @Nullable EncounterBindingAsset row) {
        String key = row == null ? null : row.getNameKey();
        return key == null ? Msg.raw(encounterId) : Message.translation(key);
    }

    @Nullable
    public static World worldOf(@Nonnull Store<EntityStore> store) {
        try {
            return store.getExternalData().getWorld();
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    public static UUID worldUuid(@Nonnull Store<EntityStore> store) {
        World world = worldOf(store);
        try {
            return world == null ? null : world.getWorldConfig().getUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The subject's position, else the encounter entity's, else null. */
    @Nullable
    public static TransformComponent anchorOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef,
            @Nullable Ref<EntityStore> subjectRef) {
        TransformComponent transform = subjectRef != null && subjectRef.isValid()
                ? store.getComponent(subjectRef, TransformComponent.getComponentType()) : null;
        if (transform == null && encounterRef.isValid()) {
            transform = store.getComponent(encounterRef, TransformComponent.getComponentType());
        }
        return transform;
    }

    private static double subjectHealthFraction(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run, @Nullable EncounterBindingAsset row) {
        if (encounterRef == null) {
            return -1.0;
        }
        try {
            Ref<EntityStore> subject = EncounterSubjects.resolve(store, encounterRef,
                    row == null ? null : row.getSubject(), row != null);
            if (subject == null) {
                return -1.0;
            }
            EntityStatMap stats = store.getComponent(subject, EntityStatsModule.get().getEntityStatMapComponentType());
            EntityStatValue health = stats == null ? null : stats.get(DefaultEntityStatTypes.getHealth());
            if (health == null || health.getMax() <= 0.0F) {
                return -1.0;
            }
            return Math.max(0.0, Math.min(1.0, health.get() / health.getMax()));
        } catch (Throwable t) {
            return -1.0;
        }
    }

    @Nonnull
    private static String feedbackId(@Nullable EncounterBindingAsset row,
            @Nonnull Function<EncounterBindingAsset.Feedback, String> leaf, @Nonnull String fallback) {
        EncounterBindingAsset.Feedback feedback = row == null ? null : row.getFeedback();
        return feedback == null ? fallback : leaf.apply(feedback);
    }

    @Nonnull
    private static Map<String, Object> baseArgs(@Nonnull ZigEncounterRun run, @Nonnull String encounterId,
            @Nullable EncounterBindingAsset row, int members, long now) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(EncounterFeedback.ENCOUNTER_ARG, encounterId);
        args.put(EncounterFeedback.TITLE_ARG, titleOf(encounterId, row));
        args.put(EncounterFeedback.MEMBERS_ARG, members);
        args.put(EncounterFeedback.SECONDS_ARG, run.elapsedMs(now) / 1000L);
        if (run.phase() != null) {
            args.put(EncounterFeedback.PHASE_ARG, run.phase());
        }
        return args;
    }
}
