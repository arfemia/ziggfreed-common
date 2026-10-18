package com.ziggfreed.common.track.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.util.SafeLog;

/**
 * The per-tick sums of a frequent kind ({@link TrackSources#batches}): every moment's pay is added to its player,
 * track and source's sum, and the first one of a tick queues one task on the world thread that pays every sum as
 * one {@link TrackService#add} and starts again. The same totals as one gain a moment (the fraction a gain leaves
 * is carried either way), with the gain pipeline, the save and {@code ZigTrackChangedEvent} run once per batch.
 * World thread only: each world keeps its own sums, filled and paid on its own thread, and dropped with it.
 */
final class SourceBatch {

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

    private static final Map<World, Pending> PENDING = new ConcurrentHashMap<>();

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
                world.execute(() -> pay(pending));
            } catch (Throwable t) {
                pending.queued = false;
                pending.sums.clear();
                SafeLog.fine("[track] a world's summed source gains could not be queued: " + t.getMessage());
            }
        }
    }

    /** Pay every sum the world holds, once each, and start again. World thread. */
    private static void pay(@Nonnull Pending pending) {
        List<Sum> due = new ArrayList<>(pending.sums.values());
        pending.sums.clear();
        pending.queued = false;
        for (Sum sum : due) {
            if (!(sum.amount > 0.0)) {
                continue;
            }
            try {
                TrackService.add(TrackKey.player(sum.player, sum.trackId), sum.amount, sum.label);
            } catch (Throwable t) {
                SafeLog.warn("[track] a summed gain on '" + sum.trackId + "' could not be paid: " + t.getMessage());
            }
        }
    }
}
