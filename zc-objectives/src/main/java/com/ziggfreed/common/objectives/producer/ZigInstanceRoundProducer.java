package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.instance.metadata.InstanceRoundCompletedEvent;
import com.ziggfreed.common.instance.metadata.RoundMetadata;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a finished instance ROUND into quest and achievement progress: {@code INSTANCE_ROUND_ENDED}
 * for every participant, {@code INSTANCE_ROUND_WON} for every winner.
 *
 * <p><b>Alone among this library's producers it is an EVENT-BUS listener, not an ECS system.</b> The
 * other five hang off native ECS events that arrive already carrying the acting entity; a round
 * ending is not something that happens TO an entity, it is something a minigame announces about a
 * group of players, so the moment arrives as
 * {@link InstanceRoundCompletedEvent} on the shared bus and the entity for each player has to be
 * looked up. Everything downstream is identical - one {@link ProgressDispatch#fire} per player, no
 * engine held, no subject built here, and like every other producer it hands its own typed record
 * along, an {@link InstanceRoundPayload} carrying the whole event, so a reaction can read the
 * round's difficulty, duration or outcome that the moment's own tuple cannot carry.
 *
 * <p><b>The contract authored content sees</b> (the same one
 * {@code ObjectiveKindRegistry} states for the two kinds):
 * {@code Target} is {@code <modId>:<modeId>} from the round's {@link RoundMetadata}, so the PREFIX
 * {@code kweebec:} matches any mode of that mod; {@code Qualifier} is the preset id; {@code Amount}
 * is 1 per round. A round whose metadata names no mode still composes the trailing {@code ':'}, so
 * the mod-prefix form keeps matching it - dropping the colon would make a mod's own prefix stop
 * addressing exactly the rounds that named no mode.
 *
 * <p><b>ENDED fires for everyone who was there, WON only for the winners</b>, which is what keeps
 * "play ten rounds" and "win ten rounds" two separate objectives. A winner therefore gets BOTH, once
 * each, on the same round.
 *
 * <p><b>Which world each player is dispatched on.</b> The event carries no world - it is a flat
 * pure-data payload by design - and its participants are not guaranteed to be anywhere in particular
 * by the time it fires (somebody may already have been sent home). So the world is resolved PER
 * PLAYER, by {@link PlayerMomentDispatch}, the engine half every bus producer shares: inline when
 * that world is the thread already running (the normal case, since a round fires on the instance
 * world its players are standing in), else with a hop onto it.
 */
public final class ZigInstanceRoundProducer {

    /** Fired once per WINNER, and only when the round was won. */
    public static final String KIND_WON = "INSTANCE_ROUND_WON";

    /** Fired once per PARTICIPANT, on every completion, won or not. */
    public static final String KIND_ENDED = "INSTANCE_ROUND_ENDED";

    /** One round is one moment for each player it names. */
    private static final long AMOUNT = 1L;

    private ZigInstanceRoundProducer() {
    }

    /**
     * Listen for round completions on the shared event bus. Registration only, from
     * {@code ProgressionDefaults.install} beside the five ECS producers; the kind decision stays
     * here.
     */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(InstanceRoundCompletedEvent.class,
                ZigInstanceRoundProducer::onRoundCompleted);
    }

    /**
     * The bus entry point. Guarded whole: a producer that throws would take the fire down with it,
     * and a listener is a courtesy the round must survive.
     */
    static void onRoundCompleted(@Nonnull InstanceRoundCompletedEvent event) {
        try {
            InstanceRoundPayload payload = new InstanceRoundPayload(event);
            fanOut(event, (playerId, kindId, target, qualifier) ->
                    dispatch(playerId, kindId, target, qualifier, payload));
        } catch (Throwable t) {
            SafeLog.warn("[progression] instance-round progress failed", t);
        }
    }

    // ==================== the pure fan-out ====================

    /** Where one player's moment goes. A seam purely so {@link #fanOut} needs no server to test. */
    @FunctionalInterface
    interface RoundSink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier);
    }

    /**
     * Who gets which kind, and what the target and qualifier read as. The whole decision this
     * producer makes, with no engine anywhere in it.
     *
     * <p>ENDED first, then WON, so a winner's two moments arrive in the order a listener would
     * describe them ("the round ended, and you won it").
     */
    static void fanOut(@Nonnull InstanceRoundCompletedEvent event, @Nonnull RoundSink sink) {
        RoundMetadata metadata = event.metadata();
        String target = target(metadata);
        String qualifier = qualifier(metadata);
        for (UUID playerId : event.participants()) {
            sink.accept(playerId, KIND_ENDED, target, qualifier);
        }
        for (UUID playerId : event.winners()) {
            sink.accept(playerId, KIND_WON, target, qualifier);
        }
    }

    /**
     * {@code <modId>:<modeId>}. The colon is always written, so a round with no mode is still matched
     * by the {@code <modId>:} prefix an author writes to mean "any mode of that mod".
     */
    @Nonnull
    static String target(@Nonnull RoundMetadata metadata) {
        String modeId = metadata.modeId();
        return metadata.modId() + ":" + (modeId == null ? "" : modeId.trim());
    }

    /** The preset id, or null when the round named none (a qualifier is optional everywhere). */
    @Nullable
    static String qualifier(@Nonnull RoundMetadata metadata) {
        String presetId = metadata.presetId();
        if (presetId == null) {
            return null;
        }
        String trimmed = presetId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================== the engine half ====================

    /**
     * Resolve one player and feed the moment to both engines, on the world thread that player is
     * actually on; the shared bus-producer half ({@link PlayerMomentDispatch}) owns the resolution
     * and the hop.
     */
    private static void dispatch(@Nonnull UUID playerId, @Nonnull String kindId,
            @Nonnull String target, @Nullable String qualifier, @Nonnull InstanceRoundPayload payload) {
        PlayerMomentDispatch.fire("instance-round", playerId, kindId, target, qualifier, AMOUNT, payload);
    }
}
