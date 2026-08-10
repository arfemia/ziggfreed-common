package com.ziggfreed.common.ui.toast;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-player current-toast state, keyed by player UUID. Held statically (not on a page
 * instance) on purpose: pages reopen as <i>fresh</i> instances ({@code new SomePage(...)}),
 * not always {@code this}, so per-instance state would be lost on the reopen that is supposed
 * to render the toast. Any page instance reads the player's current toast from here, so the
 * toast renders regardless of which instance rebuilds.
 *
 * <p>A process-wide monotonic generation token guards stale auto-dismiss clears: a newer
 * {@link #show} overwrites the entry with a higher generation, so an older scheduled clear
 * no-ops.
 */
public final class ToastStore {

    private record Entry(@Nonnull ToastSpec spec, long gen) {}

    private static final ConcurrentHashMap<UUID, Entry> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicLong GEN = new AtomicLong();

    private ToastStore() {}

    /** Set the player's current toast; returns the generation token for the matching clear. */
    public static long show(@Nonnull UUID playerId, @Nonnull ToastSpec spec) {
        long g = GEN.incrementAndGet();
        ACTIVE.put(playerId, new Entry(spec, g));
        return g;
    }

    /** The player's current toast, or {@code null} if none is showing. */
    @Nullable
    public static ToastSpec currentFor(@Nonnull UUID playerId) {
        Entry e = ACTIVE.get(playerId);
        return e == null ? null : e.spec();
    }

    /** Remove the player's toast iff it is still the one carrying {@code gen}; true if removed. */
    public static boolean clearIfCurrent(@Nonnull UUID playerId, long gen) {
        Entry e = ACTIVE.get(playerId);
        if (e != null && e.gen() == gen) {
            return ACTIVE.remove(playerId, e);
        }
        return false;
    }
}
