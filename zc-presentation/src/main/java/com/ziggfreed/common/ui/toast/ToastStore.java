package com.ziggfreed.common.ui.toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
 *
 * <p>Behind the toast on screen each player has a short queue of ranked notices waiting their turn: one a
 * higher-ranked toast kept off ({@link #outranks}), and a ranked one cut short by a toast that is not its
 * equal ({@link #requeues}). The toast on screen and the queue change together, in one atomic step per
 * player, so a show and a clear never interleave between them. When the toast on screen ends
 * ({@link #clearIfCurrent}) the best notice waiting takes its place: the highest rank, then the earliest.
 * The queue holds {@link #QUEUE_CAP} and forgets a notice after {@link #STALE_MS}, since a notice shown long
 * after its moment reads as news about something else.
 */
public final class ToastStore {

    /** How many notices wait at most; a fuller queue drops its lowest rank, oldest first. */
    static final int QUEUE_CAP = 3;

    /** How long a notice may wait before it is dropped unseen. */
    static final long STALE_MS = 6_000L;

    /** A toast on screen; {@code arrival} is the generation it first came with, which a promotion keeps. */
    private record Entry(@Nonnull ToastSpec spec, long gen, long arrival) {}

    /** A notice waiting its turn, ordered by rank, then {@code arrival}, so one cut short keeps its place. */
    private record Waiting(@Nonnull ToastSpec spec, long arrival, long queuedAtMs) {}

    /** The toast on screen and the notices waiting behind it, replaced whole on every change. */
    private record Slot(@Nonnull Entry current, @Nonnull List<Waiting> queue) {}

    /**
     * What the hide timer does next ({@link #clearIfCurrent}): nothing when a newer toast owns the slot
     * ({@code cleared} false), go idle ({@code spec} null), or show {@code spec}, whose own hide is matched by
     * {@code gen}.
     */
    public record Next(boolean cleared, @Nullable ToastSpec spec, long gen) {

        static final Next STALE = new Next(false, null, 0L);
        static final Next IDLE = new Next(true, null, 0L);
    }

    /** The order notices take their turn in: the highest rank first, then the earliest. */
    private static final Comparator<Waiting> TURN = Comparator
            .comparingInt((Waiting w) -> -w.spec().priority())
            .thenComparingLong(Waiting::arrival);

    /** The notice a full queue drops: the lowest rank, then the oldest. */
    private static final Comparator<Waiting> FIRST_DROPPED = Comparator
            .comparingInt((Waiting w) -> w.spec().priority())
            .thenComparingLong(Waiting::arrival);

    private static final ConcurrentHashMap<UUID, Slot> SLOTS = new ConcurrentHashMap<>();
    private static final AtomicLong GEN = new AtomicLong();

    /** What {@link #show} answers when a higher-ranked toast is still on screen and keeps it. */
    public static final long HELD_BACK = -1L;

    private ToastStore() {}

    /**
     * Set the player's current toast; returns the generation token for the matching clear, or
     * {@link #HELD_BACK} when the toast still on screen outranks it ({@link #outranks}), which then
     * stays for its full time while this one waits behind it.
     */
    public static long show(@Nonnull UUID playerId, @Nonnull ToastSpec spec) {
        return show(playerId, spec, System.currentTimeMillis());
    }

    static long show(@Nonnull UUID playerId, @Nonnull ToastSpec spec, long nowMs) {
        long g = GEN.incrementAndGet();
        Entry fresh = new Entry(spec, g, g);
        Slot after = SLOTS.compute(playerId, (id, slot) -> {
            if (slot == null) {
                return new Slot(fresh, List.of());
            }
            Entry current = slot.current();
            List<Waiting> queue = unexpired(slot.queue(), nowMs);
            if (outranks(current.spec(), spec)) {
                return new Slot(current, enqueue(queue, new Waiting(spec, g, nowMs)));
            }
            if (requeues(current.spec(), spec)) {
                queue = enqueue(queue, new Waiting(current.spec(), current.arrival(), nowMs));
            }
            return new Slot(fresh, queue);
        });
        return after.current() == fresh ? g : HELD_BACK;
    }

    /**
     * Does {@code showing} keep {@code incoming} off the screen? Only when both are ranked and the one
     * showing ranks higher: an unranked toast (a page's own answer to a click) always shows, and an
     * equal rank replaces as before. A lower-ranked notice that meets a higher one still showing waits
     * behind it, and its moment's sound plays as it arrives.
     */
    static boolean outranks(@Nonnull ToastSpec showing, @Nonnull ToastSpec incoming) {
        return incoming.priority() > 0 && showing.priority() > incoming.priority();
    }

    /**
     * Does {@code showing}, cut short by {@code incoming}, wait to come back? A ranked toast does when the one
     * replacing it is not its equal: a click's own answer, or a higher rank. An equal rank replaces it for
     * good, so of two claims fired together the last is the one read.
     */
    static boolean requeues(@Nonnull ToastSpec showing, @Nonnull ToastSpec incoming) {
        return showing.priority() > 0 && incoming.priority() != showing.priority();
    }

    /** The player's current toast, or {@code null} if none is showing. */
    @Nullable
    public static ToastSpec currentFor(@Nonnull UUID playerId) {
        Slot slot = SLOTS.get(playerId);
        return slot == null ? null : slot.current().spec();
    }

    /**
     * End the player's toast iff it is still the one carrying {@code gen}, in one step with what follows:
     * the best notice waiting takes its place under a new generation, or, with none waiting or when
     * {@code promote} is false (the screen it would show on has gone), the slot empties and the queue with it.
     */
    @Nonnull
    public static Next clearIfCurrent(@Nonnull UUID playerId, long gen, boolean promote) {
        return clearIfCurrent(playerId, gen, promote, System.currentTimeMillis());
    }

    @Nonnull
    static Next clearIfCurrent(@Nonnull UUID playerId, long gen, boolean promote, long nowMs) {
        Next[] next = {Next.STALE};
        SLOTS.computeIfPresent(playerId, (id, slot) -> {
            if (slot.current().gen() != gen) {
                return slot;
            }
            List<Waiting> queue = promote ? unexpired(slot.queue(), nowMs) : List.of();
            if (queue.isEmpty()) {
                next[0] = Next.IDLE;
                return null;
            }
            Waiting best = queue.get(0);
            long g = GEN.incrementAndGet();
            next[0] = new Next(true, best.spec(), g);
            return new Slot(new Entry(best.spec(), g, best.arrival()), List.copyOf(queue.subList(1, queue.size())));
        });
        return next[0];
    }

    /** {@code queue} without the notices that waited past {@link #STALE_MS}. */
    @Nonnull
    private static List<Waiting> unexpired(@Nonnull List<Waiting> queue, long nowMs) {
        if (queue.isEmpty()) {
            return queue;
        }
        List<Waiting> out = new ArrayList<>(queue.size());
        for (Waiting w : queue) {
            if (nowMs - w.queuedAtMs() <= STALE_MS) {
                out.add(w);
            }
        }
        return out.size() == queue.size() ? queue : List.copyOf(out);
    }

    /** {@code queue} with {@code w} in its turn, held to {@link #QUEUE_CAP}. */
    @Nonnull
    private static List<Waiting> enqueue(@Nonnull List<Waiting> queue, @Nonnull Waiting w) {
        List<Waiting> out = new ArrayList<>(queue.size() + 1);
        out.addAll(queue);
        out.add(w);
        if (out.size() > QUEUE_CAP) {
            out.remove(Collections.min(out, FIRST_DROPPED));
        }
        out.sort(TURN);
        return List.copyOf(out);
    }
}
