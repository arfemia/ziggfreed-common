package com.ziggfreed.common.objectives.producer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.Allies;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.health.RecentHostileDamage;
import com.ziggfreed.common.health.ZigHealGivenEvent;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.track.source.TrackSources;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code HEAL_GIVEN} for a heal a player gives themselves or an ally, off zc-core's {@link ZigHealGivenEvent}
 * (the one heal's announcement): the one producer of the kind, so a heal counts once for a track source paying
 * healing XP, a quest and an achievement alike. A consumer never dispatches the kind itself.
 *
 * <p><b>What a heal is credited for</b> (R787, R788): only health it restores (the event's amount, never past full),
 * and of that only what creatures' hits took from the healed player in the last ten seconds
 * ({@link RecentHostileDamage#draw}, which draws their pool down by as much), so healing back a fall, fire, a hit of
 * one's own or another player's earns nothing. A heal on a foe (a player who is no ally: another party, or anyone
 * while PvP is on and the healer is in none), on a creature, or crediting nothing fires nothing.
 *
 * <p>Target {@value TrackSources#HEAL_SELF} for a heal on oneself, {@value TrackSources#HEAL_PLAYER} for one on an
 * allied player (a source pays a self-heal less with a {@code Self} row); Qualifier the heal's source
 * ({@link ZigHealGivenEvent#source}: the track or skill that healed). A track source pays the credited health exactly,
 * off the {@link HealPayload}; the moment's own amount, what a quest or an achievement counts, is the whole points of
 * it, the healer's fraction carried to their next heal ({@link #wholeOf}, dropped when they die or leave), so many
 * small heals count what they credit in all. The heal fires inside whatever healed (an interaction, a proc on a hit's own moment), so the
 * moment goes out DEFERRED onto the healer's world thread ({@link PlayerMomentDispatch#fireDeferred}) and is counted
 * once that has settled; the credit is drawn as the heal lands.
 */
public final class ZigHealProducer {

    /** The objective kind this producer feeds. */
    public static final String KIND = TrackSources.HEAL_GIVEN;

    private static final String LABEL = "heal";

    /** What a sum a hair under a whole point still counts as that point (float noise across many small heals). */
    private static final double WHOLE_SLACK = 1.0e-9;

    /**
     * Per healer, the fraction of a point their credited heals have not yet counted toward a quest or an achievement.
     * Transient and per process, one entry per player who healed, dropped when they die or leave.
     */
    private static final Map<UUID, double[]> CARRY = new ConcurrentHashMap<>();

    private ZigHealProducer() {
    }

    /**
     * Listen for heals given, and drop a healer's carried fraction when they die or leave. Registration only, from
     * {@code ProgressionDefaults.install}.
     */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(ZigHealGivenEvent.class, ZigHealProducer::onHealGiven);
        plugin.getEventRegistry().register(PlayerDisconnectEvent.class, ZigHealProducer::onDisconnect);
        plugin.getEntityStoreRegistry().registerSystem(new Died());
    }

    /** Guarded whole: a producer that throws would take the heal's other listeners down with it. */
    static void onHealGiven(@Nonnull ZigHealGivenEvent event) {
        try {
            fire(event);
        } catch (Throwable t) {
            SafeLog.warn("[progression] heal dispatch failed", t);
        }
    }

    private static void fire(@Nonnull ZigHealGivenEvent event) {
        Store<EntityStore> store = event.store();
        Ref<EntityStore> healer = event.healer();
        Ref<EntityStore> healed = event.target();
        if (!healer.isValid() || !healed.isValid() || !(event.amount() > 0f)) {
            return;
        }
        PlayerRef giver = PlayerAccess.playerRef(store, healer);
        boolean self = event.selfHeal();
        PlayerRef taker = self ? giver : PlayerAccess.playerRef(store, healed);
        if (giver == null || taker == null) {
            return;
        }
        String target = targetOf(self, !self && Allies.areAllies(giver.getUuid(), taker.getUuid(),
                Allies.pvpOn(store)));
        if (target == null) {
            return;
        }
        // The event fires before the health moves, so what the healed player is missing is read as the heal finds it.
        double missing = HealthUtil.max(store, healed) - HealthUtil.current(store, healed);
        double credited = RecentHostileDamage.draw(taker.getUuid(), event.amount(), missing);
        if (!(credited > 0.0)) {
            return;
        }
        double[] carry = CARRY.computeIfAbsent(giver.getUuid(), id -> new double[1]);
        long whole = wholeOf(carry, credited);
        PlayerMomentDispatch.fireDeferred(LABEL, giver.getUuid(), KIND, target, event.source(), whole,
                new HealPayload(healed, credited));
    }

    /**
     * Add {@code credited} to a player's {@code carry} and take out its whole points: what a quest or an achievement
     * counts this heal for, the fraction kept for the next, so many small heals count what they credit in all, never
     * one each. 0 for a credit that is not above 0. Pure.
     */
    static long wholeOf(@Nonnull double[] carry, double credited) {
        if (!(credited > 0.0) || !Double.isFinite(credited)) {
            return 0L;
        }
        double total = carry[0] + credited;
        long whole = (long) Math.floor(total + WHOLE_SLACK);
        carry[0] = Math.max(0.0, total - whole);
        return whole;
    }

    /** A player left the server: the fraction their heals carried goes. */
    static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            if (player != null) {
                CARRY.remove(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[progression] a departing player's heal fraction could not be dropped: " + t.getMessage());
        }
    }

    /** A player died: the fraction their heals carried goes. */
    public static final class Died extends DeathSystems.OnDeathSystem {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                if (player != null) {
                    CARRY.remove(player.getUuid());
                }
            } catch (Throwable t) {
                SafeLog.fine("[progression] a dead player's heal fraction could not be dropped: " + t.getMessage());
            }
        }
    }

    /**
     * A heal's Target: {@value TrackSources#HEAL_SELF} on oneself, {@value TrackSources#HEAL_PLAYER} on an allied
     * player, null (no moment) on anyone else.
     */
    @Nullable
    static String targetOf(boolean self, boolean ally) {
        if (self) {
            return TrackSources.HEAL_SELF;
        }
        return ally ? TrackSources.HEAL_PLAYER : null;
    }
}
