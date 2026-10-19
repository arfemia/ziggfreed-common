package com.ziggfreed.common.track.source;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.util.SafeLog;

/**
 * The per-tick sums of a frequent kind ({@link TrackSources#batches}): every moment's pay is added to its player,
 * track and source's sum, and the first one of a tick queues one task on the world thread that pays every sum as
 * one {@link TrackService#add} and starts again. The same totals as one gain a moment (the fraction a gain leaves
 * is carried either way), with the gain pipeline, the save and {@code ZigTrackChangedEvent} run once per batch.
 * World thread only: each world keeps its own sums, filled and paid on its own thread, and dropped with it.
 *
 * <p><b>A player leaving a world mid-tick</b> loses nothing and never pays from the wrong thread: the world's own
 * removal of the player ({@link #onLeftWorld}, on that world's thread) takes their sums out and carries them, and
 * the world they are ready in next pays them there ({@link #payCarried}, from the ready settle). A disconnect drops
 * what the player holds, at most a tick's worth: the engine announces the disconnect before it takes the player out
 * of their world ({@code Universe.removePlayer}), so {@link #onDisconnect} marks the player departing and drops what
 * they carried, and the world's removal then drops their sums instead of carrying them. Nothing carried or marked
 * outlives {@link #CARRY_MS}: both are swept at every departure, world removal and ready settle. A sum whose player
 * stands in another world than the batch's when it is paid is passed over.
 */
public final class SourceBatch {

    /** How long a carried sum waits for its player to be ready in another world, and a departure mark lasts, at most. */
    static final long CARRY_MS = 60_000L;

    /** One world's sums, by player, track and source, in the order they started. */
    private static final class Pending {
        final Map<String, Sum> sums = new LinkedHashMap<>();
        boolean queued;
    }

    /** One player's sum on one track from one source. */
    private static final class Sum {
        final UUID player;
        final String trackId;
        final String label;
        double amount;

        Sum(@Nonnull UUID player, @Nonnull String trackId, @Nonnull String label) {
            this.player = player;
            this.trackId = trackId;
            this.label = label;
        }
    }

    /** What a player took out of a world they left, and when. */
    private record Carried(@Nonnull List<Sum> sums, long atMs) {
    }

    private static final Map<World, Pending> PENDING = new ConcurrentHashMap<>();

    private static final Map<UUID, Carried> CARRIED = new ConcurrentHashMap<>();

    /** The players whose disconnect fired and whose world has not yet removed them, and when it fired. */
    private static final Map<UUID, Long> DEPARTING = new ConcurrentHashMap<>();

    static {
        WorldEvictors.registerEvictor(PENDING::remove);
    }

    private SourceBatch() {
    }

    /** Add {@code amount} to {@code player}'s sum on {@code trackId} from {@code label}. On {@code world}'s thread. */
    static void add(@Nonnull World world, @Nonnull UUID player, @Nonnull String trackId, @Nonnull String label,
            double amount) {
        Pending pending = PENDING.computeIfAbsent(world, w -> new Pending());
        String key = player + "|" + trackId + "|" + label;
        Sum sum = pending.sums.get(key);
        if (sum == null) {
            sum = new Sum(player, trackId, label);
            pending.sums.put(key, sum);
        }
        sum.amount += amount;
        if (!pending.queued) {
            pending.queued = true;
            try {
                world.execute(() -> pay(world, pending));
            } catch (Throwable t) {
                pending.queued = false;
                pending.sums.clear();
                SafeLog.fine("[track] a world's summed source gains could not be queued: " + t.getMessage());
            }
        }
    }

    /** Pay every sum the world holds, once each, and start again. World thread. */
    private static void pay(@Nonnull World world, @Nonnull Pending pending) {
        List<Sum> due = new ArrayList<>(pending.sums.values());
        pending.sums.clear();
        pending.queued = false;
        for (Sum sum : due) {
            if (!(sum.amount > 0.0)) {
                continue;
            }
            if (!standsIn(sum.player, world)) {
                SafeLog.fine("[track] a summed gain on '" + sum.trackId + "' was passed over: its player is no "
                        + "longer in the world that summed it");
                continue;
            }
            payOne(sum);
        }
    }

    private static void payOne(@Nonnull Sum sum) {
        try {
            TrackService.add(TrackKey.player(sum.player, sum.trackId), sum.amount, sum.label);
        } catch (Throwable t) {
            SafeLog.warn("[track] a summed gain on '" + sum.trackId + "' could not be paid: " + t.getMessage());
        }
    }

    /** Does {@code player} stand in {@code world} right now? */
    private static boolean standsIn(@Nonnull UUID player, @Nonnull World world) {
        Ref<EntityStore> ref = PlayerAccess.liveRef(player);
        try {
            return ref != null && WorldEvictors.worldOf(ref) == world;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== the engine's events (registered by TrackBootstrap) ====================

    /**
     * A player left a world, on that world's thread: their sums there are taken out and carried to the world they
     * are ready in next, or dropped when the player is disconnecting (marked by {@link #onDisconnect}, which the
     * engine fires first).
     */
    public static void onLeftWorld(@Nonnull RemovedPlayerFromWorldEvent event) {
        try {
            World world = event.getWorld();
            PlayerRef player = event.getHolder().getComponent(PlayerRef.getComponentType());
            if (player == null) {
                return;
            }
            UUID id = player.getUuid();
            boolean departing = DEPARTING.remove(id) != null;
            long now = System.currentTimeMillis();
            sweep(now);
            Pending pending = world == null ? null : PENDING.get(world);
            if (pending == null || pending.sums.isEmpty()) {
                return;
            }
            List<Sum> taken = new ArrayList<>();
            Iterator<Sum> it = pending.sums.values().iterator();
            while (it.hasNext()) {
                Sum sum = it.next();
                if (sum.player.equals(id)) {
                    taken.add(sum);
                    it.remove();
                }
            }
            if (taken.isEmpty() || departing) {
                return;
            }
            CARRIED.merge(id, new Carried(taken, now), (a, b) -> {
                List<Sum> both = new ArrayList<>(a.sums());
                both.addAll(b.sums());
                return new Carried(both, b.atMs());
            });
        } catch (Throwable t) {
            SafeLog.fine("[track] could not carry a departing player's summed gains: " + t.getMessage());
        }
    }

    /**
     * Pay what {@code player} carried out of the world they left, now that they stand in another: the ready
     * settle's to call, on their new world's thread. A player ready here is not departing.
     */
    public static void payCarried(@Nullable UUID player) {
        long now = System.currentTimeMillis();
        sweep(now);
        if (player == null) {
            return;
        }
        DEPARTING.remove(player);
        Carried carried = CARRIED.remove(player);
        if (carried == null) {
            return;
        }
        for (Sum sum : carried.sums()) {
            if (sum.amount > 0.0) {
                payOne(sum);
            }
        }
    }

    /**
     * A player is leaving the server, before the engine takes them out of their world: what they carried is
     * dropped, and they are marked departing, so their world's removal drops their sums there (at most a tick's
     * worth) rather than carrying them.
     */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef ref = event.getPlayerRef();
            long now = System.currentTimeMillis();
            sweep(now);
            if (ref != null) {
                CARRIED.remove(ref.getUuid());
                DEPARTING.put(ref.getUuid(), now);
            }
        } catch (Throwable t) {
            SafeLog.fine("[track] could not drop a departing player's carried gains: " + t.getMessage());
        }
    }

    /** Drop every carried sum and departure mark older than {@link #CARRY_MS}. */
    private static void sweep(long now) {
        CARRIED.values().removeIf(carried -> now - carried.atMs() > CARRY_MS);
        DEPARTING.values().removeIf(at -> now - at > CARRY_MS);
    }
}
