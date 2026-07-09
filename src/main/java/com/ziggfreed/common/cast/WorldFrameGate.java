package com.ziggfreed.common.cast;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A once-per-world-frame gate. {@link #beginFrame()} returns {@code true} for only the
 * FIRST call in a given millisecond and {@code false} for every later call in the same
 * millisecond, via a compare-and-set on the last-seen millisecond stamp.
 *
 * <p>A per-world tick system fires this gate once per online player per frame; the gate
 * lets only the first call per world per frame through, so a per-world drain runs exactly
 * once. Hold ONE gate per world (keyed by {@link com.hypixel.hytale.server.core.universe.world.World})
 * so two worlds ticking in the same millisecond never steal each other's frame - a
 * single global stamp would let the first world's win skip the second world's drain.
 *
 * <p>Millisecond granularity is safe: a world ticks about once per 50ms, well above the
 * 1ms resolution. Thread-safe (CAS on an {@link AtomicLong}); no world-thread requirement.
 */
public final class WorldFrameGate {

    private final AtomicLong lastFrameMs = new AtomicLong(0L);

    /**
     * @return {@code true} for the first call in the current millisecond, {@code false}
     *         for any subsequent call in the same millisecond.
     */
    public boolean beginFrame() {
        long now = System.currentTimeMillis();
        long prev = lastFrameMs.get();
        return now != prev && lastFrameMs.compareAndSet(prev, now);
    }
}
