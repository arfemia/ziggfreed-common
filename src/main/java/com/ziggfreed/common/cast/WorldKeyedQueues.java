package com.ziggfreed.common.cast;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A generic per-world queue partition: one {@link ConcurrentLinkedQueue} of entries per
 * {@link World}, keyed by the world an entry belongs to. The shared shape behind every
 * per-world cast ticker (DOT / heal / ground-zone / beam / projectile) - each such service
 * swaps its private {@code ConcurrentHashMap<World, ConcurrentLinkedQueue<E>>} for one
 * instance of this class with NO change to its tick body.
 *
 * <p>Add at apply time via {@link #queueFor(World)} (computeIfAbsent - identical to the
 * services' inline add path). Drain reads via {@link #peek(World)} (get, nullable, so the
 * tick keeps its {@code if (q == null || q.isEmpty()) return;} guard) and iterates
 * {@link #values()} for the cross-world sweep methods (clear-matching / active-count).
 * The queue instances are themselves thread-safe, so iterating them is safe off the map.
 *
 * <p>Eviction is self-registered: the constructor registers this partition's {@code map::remove}
 * with {@link WorldEvictors}, so a removed world's queue is swept in the one JVM-global place.
 * A consumer can also drop a world early with {@link #evict(World)}.
 *
 * <p><b>No-drainer warning:</b> a drain system MUST call {@link #markDrainerAttached()} when it
 * registers. If entries are added ({@link #queueFor} is reached) before any drainer has ever
 * attached, a ONE-TIME warning fires - entries added to a partition no system ticks would leak
 * forever, so the warning surfaces the wiring mistake instead of silently piling up.
 */
public final class WorldKeyedQueues<E> {

    private final ConcurrentHashMap<World, ConcurrentLinkedQueue<E>> byWorld = new ConcurrentHashMap<>();
    private final String label;
    private final AtomicBoolean drainerAttached = new AtomicBoolean(false);
    private final AtomicBoolean warnedNoDrainer = new AtomicBoolean(false);

    /**
     * @param label a short, mod-agnostic name for this partition ("DOT", "heal", "beam", ...),
     *              prefixed onto the no-drainer warning so the wiring mistake is identifiable.
     */
    public WorldKeyedQueues(@Nonnull String label) {
        this.label = label;
        WorldEvictors.registerEvictor(byWorld::remove);
    }

    /**
     * The world's queue, created on first access (computeIfAbsent). The add path: a caller
     * does {@code queues.queueFor(world).offer(entry)}. Fires the one-time no-drainer warning
     * when entries are being added before any drainer has attached.
     */
    @Nonnull
    public ConcurrentLinkedQueue<E> queueFor(@Nonnull World world) {
        warnIfNoDrainer();
        return byWorld.computeIfAbsent(world, k -> new ConcurrentLinkedQueue<>());
    }

    /**
     * The world's queue WITHOUT creating one (map get). {@code null} when the world has no
     * queue yet - a drain body checks {@code peek(world) == null || peek(world).isEmpty()}.
     */
    @Nullable
    public ConcurrentLinkedQueue<E> peek(@Nonnull World world) {
        return byWorld.get(world);
    }

    /** True when the world has no queue or an empty one. */
    public boolean isEmpty(@Nonnull World world) {
        ConcurrentLinkedQueue<E> q = byWorld.get(world);
        return q == null || q.isEmpty();
    }

    /** Every live world's queue, for a cross-world sweep (clear-matching / active-count). */
    @Nonnull
    public Collection<ConcurrentLinkedQueue<E>> values() {
        return byWorld.values();
    }

    /** Total entry count across every world - diagnostics / active-count. */
    public int totalSize() {
        int n = 0;
        for (ConcurrentLinkedQueue<E> q : byWorld.values()) {
            n += q.size();
        }
        return n;
    }

    /** Drop a world's queue (also invoked automatically via the registered {@link WorldEvictors} evictor). */
    public void evict(@Nonnull World world) {
        byWorld.remove(world);
    }

    /** A drain system MUST call this when it registers, so the no-drainer warning stays silent. */
    public void markDrainerAttached() {
        drainerAttached.set(true);
    }

    private void warnIfNoDrainer() {
        if (!drainerAttached.get() && warnedNoDrainer.compareAndSet(false, true)) {
            warn("[" + label + "] cast queue has entries but no drain system is registered"
                    + " - entries will never tick");
        }
    }

    private static void warn(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
