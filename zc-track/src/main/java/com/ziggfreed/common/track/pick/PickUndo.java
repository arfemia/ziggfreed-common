package com.ziggfreed.common.track.pick;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The server's undo state for picks (R650): each pick a player makes can be undone until the page they made it on
 * closes, and never more than {@link #WINDOW_MS} after it was made, so a choice is never swapped mid-fight. Held
 * here, never by the page: a page only says when it closes ({@link PickService#closeUndo}). Transient and
 * single-process: a restart or a disconnect closes every window, and a prestige or a respec closes its track's.
 */
final class PickUndo {

    /** How long after it was made a pick can be undone, at most: five minutes. */
    static final long WINDOW_MS = 5L * 60L * 1000L;

    /** The most picks a player's window holds; the oldest goes first. */
    static final int MAX_ENTRIES = 64;

    /**
     * One pick that can be undone: a choice ({@code slot} set, its {@code options}, and what a Remember prestige had
     * put aside there, given back on undo) or a pick bought outright ({@code slot} null).
     */
    record Entry(@Nonnull String trackId, @Nonnull String pickId, @Nullable String slot,
                 @Nonnull List<String> options, @Nonnull List<String> remembered, long atMs) {

        Entry {
            options = List.copyOf(options);
            remembered = List.copyOf(remembered);
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
