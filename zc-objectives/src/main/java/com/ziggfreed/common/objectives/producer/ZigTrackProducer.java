package com.ziggfreed.common.objectives.producer;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackObjectives;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.event.ZigTrackChangedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a climb into quest and achievement progress, both kinds value-based (a step keeps the highest it has
 * seen; {@link TrackObjectives} is the contract): {@code TRACK_LEVEL} (Target the track id, Amount the best level
 * ever reached), and {@code TRACK_TIER}, Qualifier the track id, once per tier that level has reached (Target the
 * tier id, Amount 1, so a step naming a tier reads 1) and once more counting them (Target blank, Amount how many
 * tiers it has reached, so a step naming no tier reads the count). All are restated whenever the best level rises
 * ({@code ZigTrackChangedEvent}, a derived track reading the moved one restated with it) and once for every usable
 * track at each ready (a login or a world change), so a
 * step accepted after the level was reached settles at the next of either, and a restatement changes nothing.
 * Only the default climb (no namespace, no run) is credited.
 *
 * <p>An EVENT-BUS listener like {@link ZigReputationProducer}. A change can land inside a reward payout (a quest
 * paying a Track reward), so every moment goes out DEFERRED onto the player's world thread
 * ({@link PlayerMomentDispatch#fireDeferred}) and is counted once the payout has settled.
 */
public final class ZigTrackProducer {

    private static final String LABEL = "track";

    private ZigTrackProducer() {
    }

    /** Listen for climbs and readies on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(ZigTrackChangedEvent.class, ZigTrackProducer::onChanged);
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ZigTrackProducer::onPlayerReady);
    }

    /** Where one player's moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface Sink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount);
    }

    /** Guarded whole: a producer that throws would take the change's other listeners down with it. */
    static void onChanged(@Nonnull ZigTrackChangedEvent event) {
        try {
            UUID playerId = event.playerId();
            if (playerId == null || !event.isDefaultClimb() || event.bestAfter() <= event.bestBefore()) {
                return;
            }
            TrackDef def = TrackService.def(event.trackId());
            if (def != null) {
                fanOut(playerId, def, event.bestAfter(), deferred());
            }
            // A derived track reading this one may have moved with it: restate it too (value-based, so a restatement
            // that moved nothing changes nothing).
            for (TrackDef derived : TrackService.derivedOver(event.trackId())) {
                TrackReading reading = TrackService.read(TrackKey.player(playerId, derived.asset().getId()));
                if (reading != null) {
                    fanOut(playerId, derived, reading.best(), deferred());
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] track progress failed", t);
        }
    }

    /** At a ready: restate every usable track's level and tiers, on a later world task once the player is in. */
    static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> restate(ref));
        } catch (Throwable t) {
            SafeLog.warn("[progression] the login track progress could not be queued: " + t.getMessage());
        }
    }

    private static void restate(@Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            UUID playerId = player == null ? null : player.getUuid();
            if (playerId == null) {
                return;
            }
            Sink sink = deferred();
            for (TrackDef def : TrackService.defs()) {
                TrackReading reading = TrackService.read(TrackKey.player(playerId, def.asset().getId()));
                if (reading != null) {
                    fanOut(playerId, def, reading.best(), sink);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] the login track progress failed: " + t.getMessage());
        }
    }

    @Nonnull
    private static Sink deferred() {
        return (playerId, kindId, target, qualifier, amount) -> PlayerMomentDispatch.fireDeferred(
                LABEL, playerId, kindId, target, qualifier, amount, null);
    }

    /**
     * One {@code TRACK_LEVEL} at {@code best}; one {@code TRACK_TIER} per tier {@code best} has reached, at 1; and,
     * when it has reached any, one {@code TRACK_TIER} counting them, on the blank target only a step naming no tier
     * reads.
     *
     * @return how many moments went out
     */
    static int fanOut(@Nonnull UUID playerId, @Nonnull TrackDef def, int best, @Nonnull Sink sink) {
        String trackId = def.id();
        sink.accept(playerId, TrackObjectives.LEVEL, trackId, null, best);
        int fired = 1;
        List<CurveTable.Tier> reached = def.table().tiersReached(best);
        for (CurveTable.Tier tier : reached) {
            sink.accept(playerId, TrackObjectives.TIER, tier.id(), trackId, TrackObjectives.TIER_REACHED);
            fired++;
        }
        if (!reached.isEmpty()) {
            sink.accept(playerId, TrackObjectives.TIER, TrackObjectives.TIER_COUNT_TARGET, trackId, reached.size());
            fired++;
        }
        return fired;
    }
}
