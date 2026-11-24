package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.HeldItemUtil;
import com.ziggfreed.common.pvp.ZigPlayerKilledEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns the PvP ledger's credited deaths ({@link ZigPlayerKilledEvent}, zc-world) into PvP's own three moments, never
 * {@code DEAL_DAMAGE} or {@code KILL_ENTITY} with a player target, so a track source, a quest step, an achievement and
 * a statistic each count them once:
 * <ul>
 *   <li>{@value #KILL}: the killing blow, 1 a kill, worth the fallen player's health;</li>
 *   <li>{@value #ASSIST}: every credited player's share of the kill, the killing blow's included, 1 an assist, worth
 *       the fallen player's health times the share (so a source paying kills and assists half each pays a solo kill
 *       the whole health bar and splits a shared one);</li>
 *   <li>{@value #BOUNTY}: the bounty the death ended, for its hunter, 1 a bounty, worth the bounty's worth.</li>
 * </ul>
 * Target always {@value #TARGET}, Qualifier the credited player's held item, each worth carried exactly on a
 * {@link PvpPayload} (times what the repeat guard counted it for) and naming the fallen player, so a source's
 * {@code Victim} key counts each victim apart. The rules (who counts, the repeat guard, an idle victim, a party member)
 * are the ledger's; this only fans out. An event-bus listener: the event fires inside the engine's death system, so
 * every moment goes out deferred onto its player's world thread ({@link PlayerMomentDispatch#fireDeferred}). A consumer
 * never dispatches these kinds itself, or each counts twice.
 */
public final class ZigPvpProducer {

    /** The killing blow on another player. */
    public static final String KILL = "PLAYER_KILL";

    /** A share in another player's defeat. */
    public static final String ASSIST = "PLAYER_ASSIST";

    /** A bounty ended. */
    public static final String BOUNTY = "PLAYER_BOUNTY";

    /** The one Target the three kinds carry. */
    public static final String TARGET = "Player";

    private static final String LABEL = "pvp";

    private ZigPvpProducer() {
    }

    /** Listen for credited deaths on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(ZigPlayerKilledEvent.class, ZigPvpProducer::onKilled);
    }

    /** Where one player's moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface Sink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nullable String qualifier,
                @Nonnull PvpPayload payload);
    }

    /** Guarded whole: a producer that throws would take the death's other listeners down with it. */
    static void onKilled(@Nonnull ZigPlayerKilledEvent event) {
        try {
            fanOut(event, ZigPvpProducer::heldItemOf, (playerId, kindId, qualifier, payload) ->
                    PlayerMomentDispatch.fireDeferred(LABEL, playerId, kindId, TARGET, qualifier, 1L, payload));
        } catch (Throwable t) {
            SafeLog.warn("[progression] PvP moments failed", t);
        }
    }

    /** The held item of whoever a credit names, read on the death's thread (their ref may be in another world). */
    @Nullable
    private static String heldItemOf(@Nonnull Ref<EntityStore> player) {
        try {
            return player.isValid() ? HeldItemUtil.heldItemId(player.getStore(), player) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** How a credit's held item is read; a seam so the fan-out tests with none. */
    @FunctionalInterface
    interface HeldItems {

        @Nullable
        String of(@Nonnull Ref<EntityStore> player);
    }

    /**
     * One {@value #KILL} for the killing blow, one {@value #ASSIST} per credit with a share, and one {@value #BOUNTY}
     * for a bounty ended.
     *
     * @return how many moments went out
     */
    static int fanOut(@Nonnull ZigPlayerKilledEvent event, @Nonnull HeldItems held, @Nonnull Sink sink) {
        double health = Double.isFinite(event.victimMaxHealth()) ? Math.max(0.0, event.victimMaxHealth()) : 0.0;
        int sent = 0;
        for (ZigPlayerKilledEvent.Credit credit : event.credits()) {
            String qualifier = held.of(credit.player());
            if (credit.killingBlow()) {
                sink.accept(credit.playerId(), KILL, qualifier,
                        new PvpPayload(event.victim(), health * credit.weight(), 1.0, credit.weight()));
                sent++;
            }
            if (credit.share() > 0.0) {
                sink.accept(credit.playerId(), ASSIST, qualifier,
                        new PvpPayload(event.victim(), health * credit.share() * credit.weight(), credit.share(),
                                credit.weight()));
                sent++;
            }
        }
        ZigPlayerKilledEvent.Bounty bounty = event.bounty();
        if (bounty != null) {
            double worth = Double.isFinite(bounty.worth()) ? Math.max(0.0, bounty.worth()) : 0.0;
            sink.accept(bounty.hunterId(), BOUNTY, held.of(bounty.hunter()),
                    new PvpPayload(event.victim(), worth, 1.0, 1.0));
            sent++;
        }
        return sent;
    }
}
