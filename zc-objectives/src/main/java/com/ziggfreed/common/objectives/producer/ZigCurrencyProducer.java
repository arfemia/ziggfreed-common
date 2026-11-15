package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.currency.ZigCurrencyEarnedEvent;
import com.ziggfreed.common.currency.ZigCurrencySpentEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a wallet moving into quest and achievement progress: {@code EARN_CURRENCY} for every genuine payout
 * ({@link ZigCurrencyEarnedEvent}, Amount what landed) and {@code SPEND_CURRENCY} for every genuine charge
 * ({@link ZigCurrencySpentEvent}, Amount what was paid), Target the wallet id. The one producer of each, so a
 * wallet's moment counts once for every reader (a stat file's {@code From}, a quest, an achievement); a refund,
 * a rollback, an admin set and a loss fire nothing, by the currency engine's own rule.
 *
 * <p>An EVENT-BUS listener. A payout can land inside an engine's own earn or claim (a quest paying a wallet), so
 * every moment goes out DEFERRED onto the player's world thread ({@link PlayerMomentDispatch#fireDeferred}), as
 * {@link ZigLootReceivedProducer}'s do. A wallet held by anything but an online player is skipped there.
 */
public final class ZigCurrencyProducer {

    /** A wallet paid into. */
    public static final String KIND_EARN = "EARN_CURRENCY";

    /** A wallet paid out of. */
    public static final String KIND_SPEND = "SPEND_CURRENCY";

    private static final String LABEL = "currency";

    private ZigCurrencyProducer() {
    }

    /** Listen for wallet moves on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(ZigCurrencyEarnedEvent.class, ZigCurrencyProducer::onEarned);
        plugin.getEventRegistry().registerGlobal(ZigCurrencySpentEvent.class, ZigCurrencyProducer::onSpent);
    }

    /** Guarded whole: a producer that throws would take the wallet's other listeners down with it. */
    static void onEarned(@Nonnull ZigCurrencyEarnedEvent event) {
        try {
            if (event.amount() > 0L) {
                PlayerMomentDispatch.fireDeferred(LABEL, event.playerId(), KIND_EARN, event.currencyId(), null,
                        event.amount(), null);
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] earn-currency progress failed", t);
        }
    }

    /** Guarded whole, as {@link #onEarned}. */
    static void onSpent(@Nonnull ZigCurrencySpentEvent event) {
        try {
            if (event.amount() > 0L) {
                PlayerMomentDispatch.fireDeferred(LABEL, event.playerId(), KIND_SPEND, event.currencyId(), null,
                        event.amount(), null);
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] spend-currency progress failed", t);
        }
    }
}
