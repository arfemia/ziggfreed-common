package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * The router's questions about a world, answered by the running server: is it loaded and running, and how
 * long its ticks have taken lately. Callable from any thread; it reads the world's own tick history, which
 * the world thread writes, so the answer is a moment's reading and never a lock.
 */
public final class LiveWorlds implements CopyRouter.WorldProbe {

    /** The one probe. */
    public static final LiveWorlds INSTANCE = new LiveWorlds();

    /**
     * The tick history period the router reads: the engine's first, the last ten seconds
     * ({@code TickingThread}'s buffered tick lengths keep ten seconds, a minute and five minutes).
     */
    static final int RECENT_PERIOD = 0;

    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private LiveWorlds() {
    }

    @Override
    public boolean isLive(@Nonnull String world) {
        World live = world(world);
        return live != null && live.isAlive();
    }

    @Override
    public boolean isRegistered(@Nonnull String world) {
        return world(world) != null;
    }

    @Override
    public double tickAverageMs(@Nonnull String world) {
        World live = world(world);
        if (live == null) {
            return Double.NaN;
        }
        try {
            HistoricMetric ticks = live.getBufferedTickLengthMetricSet();
            return ticks == null ? Double.NaN : ticks.getAverage(RECENT_PERIOD) / NANOS_PER_MILLI;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** The loaded world of that name, or null (none, or no universe in this JVM). */
    @Nullable
    public static World world(@Nonnull String name) {
        try {
            Universe universe = Universe.get();
            return universe == null ? null : universe.getWorld(name);
        } catch (Throwable t) {
            return null;
        }
    }
}
