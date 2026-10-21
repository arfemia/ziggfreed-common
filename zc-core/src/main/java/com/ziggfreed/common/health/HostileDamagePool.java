package com.ziggfreed.common.health;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * One player's recent creature damage, the pure half of {@link RecentHostileDamage}: what a creature's hits took
 * from them in the last {@link #WINDOW_MS}, each hit fading whole once it is that old, and what a heal draws out of it.
 * Hits landing within {@link #BUCKET_MS} of the newest one join it (and fade with it, a moment early at worst), so a
 * pool never holds more than {@link #MAX_ENTRIES} entries however fast it is hit. Pure: the caller passes the moment.
 * Synchronized, since a world transfer can race a heal against a forget.
 */
final class HostileDamagePool {

    /** How long a creature's hit stays in the pool: ten seconds (R788). */
    static final long WINDOW_MS = 10_000L;

    /** How close to the newest entry a hit lands to join it rather than start its own. */
    static final long BUCKET_MS = 250L;

    /** The most entries one pool holds: one per bucket across the window, and one more. */
    static final int MAX_ENTRIES = (int) (WINDOW_MS / BUCKET_MS) + 1;

    /** One bucket: when its first hit landed, and the damage of its hits still undrawn. */
    private static final class Entry {
        final long atMs;
        double left;

        Entry(long atMs, double left) {
            this.atMs = atMs;
            this.left = left;
        }
    }

    /** Oldest first. */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    /** A creature's hit of {@code amount} landed at {@code nowMs}. Nothing for an amount that is not above 0. */
    synchronized void add(double amount, long nowMs) {
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return;
        }
        prune(nowMs);
        Entry newest = entries.peekLast();
        if (newest != null && nowMs - newest.atMs < BUCKET_MS && nowMs >= newest.atMs) {
            newest.left += amount;
            return;
        }
        entries.addLast(new Entry(nowMs, amount));
        while (entries.size() > MAX_ENTRIES) {
            entries.pollFirst();
        }
    }

    /** What the pool holds at {@code nowMs}: every hit inside the window, less what heals drew. */
    synchronized double held(long nowMs) {
        prune(nowMs);
        double total = 0.0;
        for (Entry entry : entries) {
            total += entry.left;
        }
        return total;
    }

    /**
     * Draw up to {@code wanted} out of the pool at {@code nowMs}, the oldest hits first (they fade first), and say how
     * much it gave: never more than {@code wanted}, never more than it holds, 0 for a wish that is not above 0.
     */
    synchronized double draw(double wanted, long nowMs) {
        if (!(wanted > 0.0) || !Double.isFinite(wanted)) {
            return 0.0;
        }
        prune(nowMs);
        return take(wanted);
    }

    /**
     * Hold the pool at {@code missing} at most, the health the player is missing right now, so it never keeps damage
     * already healed some other way (a potion, regeneration, food): what is over it goes, the oldest hits first. A
     * {@code missing} that is not a number changes nothing; one under 0 empties the pool.
     */
    synchronized void clampTo(double missing, long nowMs) {
        if (Double.isNaN(missing)) {
            return;
        }
        prune(nowMs);
        double total = 0.0;
        for (Entry entry : entries) {
            total += entry.left;
        }
        double over = total - Math.max(0.0, missing);
        if (over > 0.0) {
            take(over);
        }
    }

    /**
     * The health a player was missing just before a hit landed, read after it: their maximum less their health now
     * and the hit's own amount, never under 0 (a hit that took them to the floor reads short, never long). NaN when
     * either reading is not a number. Pure.
     */
    static double missingBefore(double current, double max, double hit) {
        if (!Double.isFinite(current) || !Double.isFinite(max)) {
            return Double.NaN;
        }
        double applied = Double.isFinite(hit) ? Math.max(0.0, hit) : 0.0;
        return Math.max(0.0, max - current - applied);
    }

    /** Take up to {@code wanted} from the oldest entries on, and say how much was taken. Pruned already. */
    private double take(double wanted) {
        double taken = 0.0;
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext() && taken < wanted) {
            Entry entry = it.next();
            double part = Math.min(entry.left, wanted - taken);
            entry.left -= part;
            taken += part;
            if (!(entry.left > 0.0)) {
                it.remove();
            }
        }
        return taken;
    }

    /** Does the pool hold nothing at {@code nowMs}? */
    synchronized boolean isEmpty(long nowMs) {
        prune(nowMs);
        return entries.isEmpty();
    }

    /** Drop every entry that has faded by {@code nowMs}: one whose first hit landed a whole window ago or more. */
    private void prune(long nowMs) {
        while (!entries.isEmpty() && nowMs - entries.peekFirst().atMs >= WINDOW_MS) {
            entries.pollFirst();
        }
    }
}
