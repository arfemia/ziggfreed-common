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

    /** What {@link #show} answers when a higher-ranked toast is still on screen and keeps it. */
    public static final long HELD_BACK = -1L;

    private ToastStore() {}

    /**
     * Set the player's current toast; returns the generation token for the matching clear, or
     * {@link #HELD_BACK} when the toast still on screen outranks it ({@link #outranks}), which then
     * stays for its full time while this one is dropped.
     */
    public static long show(@Nonnull UUID playerId, @Nonnull ToastSpec spec) {
        long g = GEN.incrementAndGet();
        Entry fresh = new Entry(spec, g);
        Entry kept = ACTIVE.compute(playerId,
                (id, current) -> current != null && outranks(current.spec(), spec) ? current : fresh);
        return kept == fresh ? g : HELD_BACK;
    }

    /**
     * Does {@code showing} keep {@code incoming} off the screen? Only when both are ranked and the one
     * showing ranks higher: an unranked toast (a page's own answer to a click) always shows, and an
     * equal rank replaces as before. A lower-ranked notice that meets a higher one still showing is
     * dropped from the page, not queued: it still shows in the book (and on the quest tracker), and its
     * moment's sound still plays.
     */
    static boolean outranks(@Nonnull ToastSpec showing, @Nonnull ToastSpec incoming) {
        return incoming.priority() > 0 && showing.priority() > incoming.priority();
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
