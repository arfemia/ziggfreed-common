package com.ziggfreed.common.track.pick;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.cost.Cost;
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
     * one undo gives back the whole batch, however long. A take that was paid for keeps its charge ({@code paid}),
     * whose receipt the undo gives back; null for one that cost no price. A player's own respec of a kind of pick is
     * one entry too ({@code respec}, its fee as {@code paid}), however many tracks it cleared.
     */
    record Entry(@Nonnull String trackId, @Nonnull String pickId, @Nullable String slot,
                 @Nonnull List<String> options, @Nonnull List<String> remembered, long atMs,
                 @Nonnull List<String> batch, @Nullable PickPayments.Paid paid, @Nullable KindRespec respec) {

        Entry {
            options = List.copyOf(options);
            remembered = List.copyOf(remembered);
            batch = List.copyOf(batch);
        }

        /** A choice, a take or a batch, its charge ({@code paid}, null for none): no kind respec. */
        Entry(@Nonnull String trackId, @Nonnull String pickId, @Nullable String slot, @Nonnull List<String> options,
                @Nonnull List<String> remembered, long atMs, @Nonnull List<String> batch,
                @Nullable PickPayments.Paid paid) {
            this(trackId, pickId, slot, options, remembered, atMs, batch, paid, null);
        }

        /**
         * A player's own respec of a kind of pick ({@code PickService.respecKind}), on every track it cleared: filed
         * under its first track, named by its respec's id.
         */
        @Nonnull
        static Entry kindRespec(@Nonnull KindRespec respec, long atMs) {
            String first = respec.after().isEmpty() ? "" : respec.after().keySet().iterator().next();
            return new Entry(first, respec.respecId(), null, List.of(), List.of(), atMs, List.of(), respec.fee(),
                    respec);
        }

        /** Does this entry touch {@code trackId}: filed under it, or a kind respec that cleared picks there? */
        boolean touches(@Nonnull String trackId) {
            return trackId.equals(this.trackId) || (respec != null && respec.after().containsKey(trackId));
        }

        /** A choice, a free take or a batch: nothing was charged. */
        Entry(@Nonnull String trackId, @Nonnull String pickId, @Nullable String slot, @Nonnull List<String> options,
                @Nonnull List<String> remembered, long atMs, @Nonnull List<String> batch) {
            this(trackId, pickId, slot, options, remembered, atMs, batch, null);
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

    /**
     * What a kind respec did, for its undo: each track's picks before and after it (by lower-cased track id, in the
     * order cleared), the fee's charge to give back, what it handed the player to take back, and how many picks it
     * cleared. An undo puts every track back only while each still holds exactly what the respec left.
     */
    record KindRespec(@Nonnull String respecId, @Nonnull Map<String, PickState> before,
                      @Nonnull Map<String, PickState> after, @Nonnull PickPayments.Paid fee, @Nonnull Cost given,
                      int cleared) {

        KindRespec {
            before = Collections.unmodifiableMap(new LinkedHashMap<>(before));
            after = Collections.unmodifiableMap(new LinkedHashMap<>(after));
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

    /**
     * Close {@code player}'s window on {@code trackId} alone (a prestige or a respec of it), a kind respec that
     * cleared picks there with it.
     */
    static void closeTrack(@Nullable UUID player, @Nonnull String trackId) {
        Deque<Entry> held = player == null ? null : OPEN.get(player);
        if (held == null) {
            return;
        }
        synchronized (held) {
            held.removeIf(entry -> entry.touches(trackId));
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
