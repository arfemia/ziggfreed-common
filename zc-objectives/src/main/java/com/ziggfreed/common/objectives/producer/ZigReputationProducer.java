package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.reputation.event.ZigReputationRanksHeldEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a reputation rank check into quest and achievement progress: one {@code REPUTATION_RANK} per rank
 * the player holds, Target the rank id, Qualifier the reputation id, Amount 1.
 *
 * <p>An EVENT-BUS listener like {@link ZigLootReceivedProducer}. A check can run inside a reward payout
 * (a quest paying a Reputation reward), so every moment goes out DEFERRED onto the player's world thread
 * ({@link PlayerMomentDispatch#fireDeferred}) and is counted once the payout has settled. The same ranks
 * are re-stated at every login and after every change, and a step already met ignores a repeat.
 */
public final class ZigReputationProducer {

    /** Fired once per rank held, per check. */
    public static final String KIND = "REPUTATION_RANK";

    private static final long AMOUNT = 1L;

    private static final String LABEL = "reputation";

    private ZigReputationProducer() {
    }

    /** Listen for rank checks on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(ZigReputationRanksHeldEvent.class, ZigReputationProducer::onRanksHeld);
    }

    /** Where one player's moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface Sink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount);
    }

    /** Guarded whole: a producer that throws would take the check's other listeners down with it. */
    static void onRanksHeld(@Nonnull ZigReputationRanksHeldEvent event) {
        try {
            fanOut(event, (playerId, kindId, target, qualifier, amount) -> PlayerMomentDispatch.fireDeferred(
                    LABEL, playerId, kindId, target, qualifier, amount, null));
        } catch (Throwable t) {
            SafeLog.warn("[progression] reputation rank progress failed", t);
        }
    }

    /**
     * One moment per rank held.
     *
     * @return how many moments went out
     */
    static int fanOut(@Nonnull ZigReputationRanksHeldEvent event, @Nonnull Sink sink) {
        int fired = 0;
        for (String rank : event.ranks()) {
            sink.accept(event.playerId(), KIND, rank, event.reputationId(), AMOUNT);
            fired++;
        }
        return fired;
    }
}
