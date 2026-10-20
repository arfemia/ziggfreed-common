package com.ziggfreed.common.track.pick;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.track.PickState;

/**
 * The server's undo state for picks (R650): each pick a player makes can be undone until the page they made it on
 * closes, and never more than {@link #WINDOW_MS} after it was made, so a choice is never swapped mid-fight. A batch
 * of takes made in one go (a starter build spent, remembered takes re-spent) is one entry, undone whole. Held
 * here, never by the page: a page only says when it closes ({@link PickService#closeUndo}). Transient and
 * single-process: a restart or a disconnect closes every window, and a prestige or a respec closes its track's.
 */
final class PickUndo {

    /** How long after it was made a pick can be undone, at most: five minutes. */
    static final long WINDOW_MS = 5L * 60L * 1000L;

    /** The most entries a player's window holds; the oldest goes first. A batch is one entry, however many takes. */
    static final int MAX_ENTRIES = 64;

    /**
     * One thing that can be undone: a choice ({@code slot} set, its {@code options}, and what a Remember prestige had
     * put aside there, given back on undo), a pick bought outright ({@code slot} null), or a batch of takes made in
     * one go ({@code batch}: each take's pick id in the order made, a repeat for each further rank; a re-spend of
     * remembered takes also lists them in {@code remembered}, put back at the head of what is remembered on undo), so
     * one undo gives back the whole batch, however long.
     */
    record Entry(@Nonnull String trackId, @Nonnull String pickId, @Nullable String slot,
                 @Nonnull List<String> options, @Nonnull List<String> remembered, long atMs,
                 @Nonnull List<String> batch) {

        Entry {
            options = List.copyOf(options);
            remembered = List.copyOf(remembered);
            batch = List.copyOf(batch);
        }

        /** One choice or one take (no batch). */
        Entry(@Nonnull String trackId, @Nonnull String pickId, @Nullable String slot, @Nonnull List<String> options,
                @Nonnull List<String> remembered, long atMs) {
            this(trackId, pickId, slot, options, remembered, atMs, List.of());
        }

        /**
         * A batch of {@code takes} made in one go on {@code trackId}; {@code rememberedBack} says each was a
         * remembered take, put back at the head of what is remembered on undo.
         */
        @Nonnull
        static Entry batch(@Nonnull String trackId, @Nonnull List<String> takes, boolean rememberedBack, long atMs) {
            String first = takes.isEmpty() ? "" : takes.get(0);
            return new Entry(trackId, first, null, List.of(), rememberedBack ? takes : List.of(), atMs, takes);
        }

        /** Is this a batch of takes rather than one choice or one take? */
        boolean isBatch() {
            return !batch.isEmpty();
        }
    }

    private static final Map<UUID, Deque<Entry>> OPEN = new ConcurrentHashMap<>();

    private PickUndo() {
    }

    /** Keep {@code entry} undoable for {@code player}. */
    static void record(@Nonnull UUID player, @Nonnull Entry entry) {
        Deque<Entry> held = OPEN.computeIfAbsent(player, id -> new ArrayDeque<>());
        synchronized (held) {
            held.addLast(entry);
            while (held.size() > MAX_ENTRIES) {
                held.removeFirst();
            }
        }
    }

    /** The latest pick {@code player} can still undo at {@code nowMs}, taken out; null when none. */
    @Nullable
    static Entry pop(@Nonnull UUID player, long nowMs) {
        Deque<Entry> held = OPEN.get(player);
        if (held == null) {
            return null;
        }
        synchronized (held) {
            expire(held, nowMs);
            return held.pollLast();
        }
    }

    /**
     * {@code state} with the batch {@code entry} given back whole (pure): each take taken back, newest first, and a
     * re-spent remembered take put back at the head of what is remembered, so the remembered list reads as it did
     * before the batch. Null when the state no longer holds every take of the batch (nothing is undone then).
     */
    @Nullable
    static PickState undoBatch(@Nonnull PickState state, @Nonnull Entry entry) {
        Map<String, Integer> needed = new HashMap<>();
        for (String take : entry.batch()) {
            needed.merge(take, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> take : needed.entrySet()) {
            if (state.takesOf(take.getKey()) < take.getValue()) {
                return null;
            }
        }
        PickState next = state;
        List<String> takes = entry.batch();
        boolean rememberedBack = !entry.remembered().isEmpty();
        for (int i = takes.size() - 1; i >= 0; i--) {
            next = next.withTakes(takes.get(i), -1);
            if (rememberedBack) {
                next = next.withRememberedTakeFirst(takes.get(i));
            }
        }
        return next;
    }

    /** Put {@code entry} back as the latest (an undo that could not be written). */
    static void putBack(@Nonnull UUID player, @Nonnull Entry entry) {
        record(player, entry);
    }

    /** Can {@code player} undo anything at {@code nowMs}? */
    static boolean any(@Nonnull UUID player, long nowMs) {
        Deque<Entry> held = OPEN.get(player);
        if (held == null) {
            return false;
        }
        synchronized (held) {
            expire(held, nowMs);
            return !held.isEmpty();
        }
    }

    /** Close {@code player}'s window: nothing they made so far can be undone any more. */
    static void close(@Nullable UUID player) {
        if (player != null) {
            OPEN.remove(player);
        }
    }

    /** Close {@code player}'s window on {@code trackId} alone (a prestige or a respec of it). */
    static void closeTrack(@Nullable UUID player, @Nonnull String trackId) {
        Deque<Entry> held = player == null ? null : OPEN.get(player);
        if (held == null) {
            return;
        }
        synchronized (held) {
            held.removeIf(entry -> entry.trackId().equals(trackId));
        }
    }

    private static void expire(@Nonnull Deque<Entry> held, long nowMs) {
        Iterator<Entry> it = held.iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().atMs() > WINDOW_MS) {
                it.remove();
            }
        }
    }
}
