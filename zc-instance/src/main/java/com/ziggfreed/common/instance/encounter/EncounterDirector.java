package com.ziggfreed.common.instance.encounter;

import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

/**
 * Mid-round wave bookkeeping for an encounter, generalized from Kweebec's escalation gating
 * ({@code AiHunterController.maybeEscalate}'s cap + rate-limit) into a PURE, engine-free director. It
 * tracks, per named rule key, the last fire time and a fire count, and gates a fire by a cooldown + a
 * max-per-round cap; it also clamps a requested wave size to the room left under a hard live cap.
 *
 * <p><b>Pure + deterministic.</b> No engine calls and no wall-clock reads: the caller passes {@code nowMs}
 * (its own monotonic/round clock) and the live entity count as parameters, so the director never reaches
 * into a {@code Store}/{@code World} and never calls {@link System#currentTimeMillis()} itself. The
 * world-thread caller owns timing and counting; this is just the gate.
 *
 * <p><b>Threading.</b> The trackers are {@link ConcurrentHashMap}s. The intended caller is the world
 * thread (one round tick), but the maps are safe under concurrent reads; the per-key fire-count bump in
 * {@link #recordFire} is atomic via {@code merge}.
 *
 * @see SpawnRoster for choosing WHAT a wave spawns; this decides WHETHER and HOW MANY.
 */
public final class EncounterDirector {

    /** ruleKey -> last fire time (ms, on the caller's clock). */
    private final ConcurrentHashMap<String, Long> lastFireMs = new ConcurrentHashMap<>();
    /** ruleKey -> number of fires recorded this round. */
    private final ConcurrentHashMap<String, Integer> fireCounts = new ConcurrentHashMap<>();

    /**
     * Whether the rule keyed by {@code ruleKey} may fire at {@code nowMs}: the cooldown since its last
     * fire has elapsed AND it has fired fewer than {@code maxFires} times this round.
     *
     * @param ruleKey    the rule identity (consumer-chosen)
     * @param nowMs      the caller's current time, on the same clock passed to {@link #recordFire}
     * @param cooldownMs minimum gap between fires; {@code <= 0} disables the cooldown gate
     * @param maxFires   maximum fires per round; {@code <= 0} disables the cap gate (unlimited)
     * @return true if a fire is allowed now
     */
    public boolean canFire(@Nonnull String ruleKey, long nowMs, long cooldownMs, int maxFires) {
        if (maxFires > 0 && fireCounts.getOrDefault(ruleKey, 0) >= maxFires) {
            return false;
        }
        if (cooldownMs > 0) {
            Long last = lastFireMs.get(ruleKey);
            if (last != null && nowMs - last < cooldownMs) {
                return false;
            }
        }
        return true;
    }

    /**
     * Record that {@code ruleKey} fired at {@code nowMs}: stamps the cooldown clock and bumps the
     * per-round fire count. Call exactly once each time {@link #canFire} gated a real spawn.
     */
    public void recordFire(@Nonnull String ruleKey, long nowMs) {
        lastFireMs.put(ruleKey, nowMs);
        fireCounts.merge(ruleKey, 1, Integer::sum);
    }

    /**
     * Clamp a requested wave size to the room left under a hard cap: {@code min(requested, hardCap -
     * liveCount)}, floored at 0. Mirrors {@code maybeEscalate}'s "never exceed the cap" guard, generalized
     * to a requested batch rather than a single add.
     *
     * @param liveCount the current live entity count (caller-supplied; engine read happens caller-side)
     * @param hardCap   the absolute ceiling on live entities
     * @param requested how many the caller would like to spawn
     * @return the number actually allowed to spawn (0..requested, never negative)
     */
    public int allowedToSpawn(int liveCount, int hardCap, int requested) {
        int room = hardCap - liveCount;
        if (room <= 0 || requested <= 0) {
            return 0;
        }
        return Math.min(requested, room);
    }

    /** How many times {@code ruleKey} has fired this round (0 if never). */
    public int fireCount(@Nonnull String ruleKey) {
        return fireCounts.getOrDefault(ruleKey, 0);
    }

    /** Clear all per-round bookkeeping (cooldowns + fire counts). Call at round start/end. */
    public void reset() {
        lastFireMs.clear();
        fireCounts.clear();
    }
}
