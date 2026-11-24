package com.ziggfreed.common.pvp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Who struck one player and for how much, lately: the half of the PvP ledger that remembers hits (R729's kill and
 * assist credit reads it at the death). Each entry is one applied hit by another player, kept in arrival order and
 * held to {@link #MAX_ENTRIES}, the oldest falling out first. Pure and in memory: no engine type, so the credit rules
 * test on it directly; its owner synchronizes on it.
 */
public final class PvpTally {

    /** The most hits one player's tally keeps. */
    public static final int MAX_ENTRIES = 128;

    /** One applied hit: who dealt it, how much it took and when. */
    record Hit(@Nonnull UUID attacker, double amount, long atMs) {
    }

    private final Deque<Hit> hits = new ArrayDeque<>();

    /** A hit of {@code amount} by {@code attacker} landed at {@code nowMs}. A non-positive amount is no hit. */
    public synchronized void add(@Nonnull UUID attacker, double amount, long nowMs) {
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return;
        }
        hits.addLast(new Hit(attacker, amount, nowMs));
        while (hits.size() > MAX_ENTRIES) {
            hits.removeFirst();
        }
    }

    /** Drop every hit older than {@code windowMs} at {@code nowMs}. */
    public synchronized void prune(long nowMs, long windowMs) {
        Iterator<Hit> it = hits.iterator();
        while (it.hasNext()) {
            if (!within(it.next(), nowMs, windowMs)) {
                it.remove();
            }
        }
    }

    /** Holds no hit at all. */
    public synchronized boolean isEmpty() {
        return hits.isEmpty();
    }

    /**
     * Each attacker's share of the damage dealt within {@code windowMs} of {@code nowMs}, in the order they first
     * struck, the shares summing to 1; empty when nobody struck in that time.
     */
    @Nonnull
    public synchronized Map<UUID, Double> shares(long nowMs, long windowMs) {
        Map<UUID, Double> dealt = new LinkedHashMap<>();
        double total = 0.0;
        for (Hit hit : hits) {
            if (within(hit, nowMs, windowMs)) {
                dealt.merge(hit.attacker(), hit.amount(), Double::sum);
                total += hit.amount();
            }
        }
        if (!(total > 0.0)) {
            return Map.of();
        }
        Map<UUID, Double> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : dealt.entrySet()) {
            out.put(entry.getKey(), entry.getValue() / total);
        }
        return out;
    }

    /** Who struck last within {@code windowMs} of {@code nowMs}, or null for nobody. */
    @Nullable
    public synchronized UUID lastAttacker(long nowMs, long windowMs) {
        Hit last = hits.peekLast();
        return last != null && within(last, nowMs, windowMs) ? last.attacker() : null;
    }

    /** Did {@code attacker} strike within {@code windowMs} of {@code nowMs}? */
    public synchronized boolean struckBy(@Nonnull UUID attacker, long nowMs, long windowMs) {
        for (Hit hit : hits) {
            if (hit.attacker().equals(attacker) && within(hit, nowMs, windowMs)) {
                return true;
            }
        }
        return false;
    }

    private static boolean within(@Nonnull Hit hit, long nowMs, long windowMs) {
        return windowMs > 0L && nowMs - hit.atMs() <= windowMs && hit.atMs() <= nowMs;
    }
}
