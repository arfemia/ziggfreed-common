package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.loot.reward.LootReceivedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a reward payout's announcement into quest and achievement progress: one
 * {@code LOOT_RECEIVED} per item the payout handed over, Target the item id, Qualifier the payout's
 * source id ({@code quest:<id>}, {@code achievement:<id>}, ...), Amount the count.
 *
 * <p>An EVENT-BUS listener like {@link ZigEncounterProducer}. The announcement fires from inside a
 * payout, often inside an engine's own earn or claim, so every moment goes out DEFERRED onto the
 * player's world thread ({@link PlayerMomentDispatch#fireDeferred}) and is counted once the payout
 * has settled, never by re-entering the engine mid-payout.
 */
public final class ZigLootReceivedProducer {

    /** Fired once per item a reward payout handed over. */
    public static final String KIND = "LOOT_RECEIVED";

    private static final String LABEL = "loot-received";

    private ZigLootReceivedProducer() {
    }

    /** Listen for payouts on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(LootReceivedEvent.class, ZigLootReceivedProducer::onReceived);
    }

    /** Guarded whole: a producer that throws would take the payout's announcement down with it. */
    static void onReceived(@Nonnull LootReceivedEvent event) {
        try {
            fanOut(event, (playerId, kindId, target, qualifier, amount) -> PlayerMomentDispatch.fireDeferred(
                    LABEL, playerId, kindId, target, qualifier, amount, null));
        } catch (Throwable t) {
            SafeLog.warn("[progression] loot-received progress failed", t);
        }
    }

    /** Where one player's moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface Sink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount);
    }

    /**
     * One moment per item received.
     *
     * @return how many moments went out
     */
    static int fanOut(@Nonnull LootReceivedEvent event, @Nonnull Sink sink) {
        String qualifier = event.sourceId().isBlank() ? null : event.sourceId();
        int fired = 0;
        for (LootReceivedEvent.Received item : event.items()) {
            sink.accept(event.playerId(), KIND, item.itemId(), qualifier, item.count());
            fired++;
        }
        return fired;
    }
}
