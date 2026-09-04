package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.event.EncounterPhaseChangedEvent;
import com.ziggfreed.common.encounter.event.EncounterWipedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a boss fight's beats into quest and achievement progress: {@code ENCOUNTER_DEFEATED} for
 * every CREDITED participant when the boss falls, {@code ENCOUNTER_ATTEMPT} for every participant
 * when a fight settles either way, {@code ENCOUNTER_PHASE} for every live member on each phase
 * beat.
 *
 * <p>An EVENT-BUS listener like {@link ZigInstanceRoundProducer}, for the same reason: a fight's
 * beat is announced about a group of players rather than happening to one entity, so it arrives as
 * a native event and each player is looked up on their own world thread
 * ({@link PlayerMomentDispatch}). It listens to the three settled beats and never to
 * {@code EncounterResetEvent}: a reset's reasons include a builder reload, a timeout and a world
 * unload, and an attempt credited off any of those would pay every member for a server restart.
 *
 * <p><b>The contract authored content sees</b> (the same one {@code ObjectiveKindRegistry} states
 * for the three kinds): {@code Target} is the encounter SCRIPT id, never the boss creature's id,
 * so a step naming the boss holds through an in-place role swap; {@code Qualifier} is the run's
 * difficulty label for the two settlement kinds and the phase's own state name for the phase kind;
 * {@code Amount} is 1 per fire.
 *
 * <p><b>Who is credited.</b> DEFEATED goes to the participants the ledger credited (a share at or
 * over the fight's {@code MinShare}); ATTEMPT goes to every participant the ledger saw, credited or
 * not, on a defeat and on a wipe alike; PHASE goes to every live member, whether or not they have
 * dealt damage yet, because a member who is still in the fight is still part of it. A settlement
 * fires ATTEMPT before DEFEATED for the same reason the round producer fires ENDED before WON: a
 * winner's two moments arrive in the order a listener would describe them.
 *
 * <p>Each fire carries an {@link EncounterPayload} with the whole event and this participant's own
 * share, and every fire of one beat shares the run id as its credit, so a world-first achievement
 * over {@code ENCOUNTER_DEFEATED} is won by the whole party rather than raced between its members.
 * One INFO line per beat says how many were dispatched, so a headless run with nobody in it reads as
 * "dispatched for 0" rather than as silence.
 */
public final class ZigEncounterProducer {

    /** Fired once per CREDITED participant when the boss falls. */
    public static final String KIND_DEFEATED = "ENCOUNTER_DEFEATED";

    /** Fired once per PARTICIPANT when a fight settles, won or wiped. */
    public static final String KIND_ATTEMPT = "ENCOUNTER_ATTEMPT";

    /** Fired once per live MEMBER on every phase beat, the phase's state name as the qualifier. */
    public static final String KIND_PHASE = "ENCOUNTER_PHASE";

    /** One beat is one moment for each player it names. */
    private static final long AMOUNT = 1L;

    private static final String LABEL = "encounter";

    private ZigEncounterProducer() {
    }

    /**
     * Listen for the three settled beats on the shared event bus. Registration only, from
     * {@code ProgressionDefaults.install} beside the round producer; every decision stays here.
     */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(EncounterDefeatedEvent.class, ZigEncounterProducer::onDefeated);
        plugin.getEventRegistry().registerGlobal(EncounterWipedEvent.class, ZigEncounterProducer::onWiped);
        plugin.getEventRegistry().registerGlobal(EncounterPhaseChangedEvent.class,
                ZigEncounterProducer::onPhaseChanged);
    }

    // ==================== the bus entry points ====================

    /** Guarded whole: a producer that throws would take the fire down with it. */
    static void onDefeated(@Nonnull EncounterDefeatedEvent event) {
        try {
            int fired = fanOutDefeated(event, ZigEncounterProducer::dispatch);
            SafeLog.info("[progression] " + KIND_DEFEATED + " dispatched for " + fired
                    + " credited participant(s) of " + event.encounterId() + " (run "
                    + EncounterRun.shortId(event.runId()) + ", " + event.participants().size() + " in the fight)");
        } catch (Throwable t) {
            SafeLog.warn("[progression] encounter defeat progress failed", t);
        }
    }

    static void onWiped(@Nonnull EncounterWipedEvent event) {
        try {
            int fired = fanOutWiped(event, ZigEncounterProducer::dispatch);
            SafeLog.info("[progression] " + KIND_ATTEMPT + " dispatched for " + fired
                    + " participant(s) of " + event.encounterId() + " (run "
                    + EncounterRun.shortId(event.runId()) + ", wiped)");
        } catch (Throwable t) {
            SafeLog.warn("[progression] encounter wipe progress failed", t);
        }
    }

    static void onPhaseChanged(@Nonnull EncounterPhaseChangedEvent event) {
        try {
            int fired = fanOutPhase(event, ZigEncounterProducer::dispatch);
            SafeLog.info("[progression] " + KIND_PHASE + " dispatched for " + fired + " member(s) of "
                    + event.encounterId() + " (run " + EncounterRun.shortId(event.runId()) + ", phase "
                    + event.toPhase() + ")");
        } catch (Throwable t) {
            SafeLog.warn("[progression] encounter phase progress failed", t);
        }
    }

    // ==================== the pure fan-out ====================

    /** Where one player's moment goes. A seam purely so the fan-outs need no server to test. */
    @FunctionalInterface
    interface EncounterSink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, @Nonnull EncounterPayload payload);
    }

    /**
     * A defeat: ATTEMPT for everybody the ledger saw, then DEFEATED for the credited.
     *
     * @return how many DEFEATED moments went out
     */
    static int fanOutDefeated(@Nonnull EncounterDefeatedEvent event, @Nonnull EncounterSink sink) {
        String target = event.encounterId();
        String qualifier = qualifier(event.difficulty());
        for (ParticipantShare share : event.participantShares()) {
            sink.accept(share.playerId(), KIND_ATTEMPT, target, qualifier, payload(event, share));
        }
        int credited = 0;
        for (ParticipantShare share : event.participantShares()) {
            if (!share.credited()) {
                continue;
            }
            credited++;
            sink.accept(share.playerId(), KIND_DEFEATED, target, qualifier, payload(event, share));
        }
        return credited;
    }

    /**
     * A wipe: ATTEMPT for everybody the ledger saw, credited or not.
     *
     * @return how many ATTEMPT moments went out
     */
    static int fanOutWiped(@Nonnull EncounterWipedEvent event, @Nonnull EncounterSink sink) {
        String target = event.encounterId();
        int fired = 0;
        for (ParticipantShare share : event.participantShares()) {
            fired++;
            sink.accept(share.playerId(), KIND_ATTEMPT, target, null,
                    new EncounterPayload(event.runId(), target, event, share.share()));
        }
        return fired;
    }

    /**
     * A phase beat: PHASE for every live member, the phase's own state name as the qualifier.
     *
     * @return how many PHASE moments went out
     */
    static int fanOutPhase(@Nonnull EncounterPhaseChangedEvent event, @Nonnull EncounterSink sink) {
        String target = event.encounterId();
        EncounterPayload payload = new EncounterPayload(event.runId(), target, event, null);
        int fired = 0;
        for (UUID member : event.members()) {
            fired++;
            sink.accept(member, KIND_PHASE, target, event.toPhase(), payload);
        }
        return fired;
    }

    @Nonnull
    private static EncounterPayload payload(@Nonnull EncounterDefeatedEvent event, @Nonnull ParticipantShare share) {
        return new EncounterPayload(event.runId(), event.encounterId(), event, share.share());
    }

    /** The difficulty label, or null when the run carried none (a qualifier is optional everywhere). */
    @Nullable
    static String qualifier(@Nullable String difficulty) {
        if (difficulty == null) {
            return null;
        }
        String trimmed = difficulty.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================== the engine half ====================

    private static void dispatch(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier, @Nonnull EncounterPayload payload) {
        PlayerMomentDispatch.fire(LABEL, playerId, kindId, target, qualifier, AMOUNT, payload);
    }
}
