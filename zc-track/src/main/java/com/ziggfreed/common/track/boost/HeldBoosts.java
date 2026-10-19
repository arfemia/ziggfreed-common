package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * What one subject holds of boosts, as its track store keeps it ({@code TrackStore.boosts}): its own running starts
 * and, per boost, when its wait between starts ends. Immutable; a start that has ended and a wait that is over are
 * dropped by {@link #live}, which every write keeps.
 *
 * @param runs      its own (personal) starts, in the order they began
 * @param cooldowns per boost id (lower-cased), when the next start may come, in epoch milliseconds
 */
public record HeldBoosts(@Nonnull List<BoostRun> runs, @Nonnull Map<String, Long> cooldowns) {

    /** Nothing held: what a player who never started a boost holds. */
    public static final HeldBoosts EMPTY = new HeldBoosts(List.of(), Map.of());

    public HeldBoosts {
        List<BoostRun> kept = new ArrayList<>(runs.size());
        for (BoostRun run : runs) {
            if (run != null && !run.boost().isEmpty()) {
                kept.add(run);
            }
        }
        runs = List.copyOf(kept);
        Map<String, Long> waits = new HashMap<>();
        for (Map.Entry<String, Long> wait : cooldowns.entrySet()) {
            if (wait.getKey() != null && !wait.getKey().isBlank() && wait.getValue() != null && wait.getValue() > 0L) {
                waits.merge(key(wait.getKey()), wait.getValue(), Math::max);
            }
        }
        cooldowns = Map.copyOf(waits);
    }

    /** This record without what has ended by {@code nowMs}: starts that ran out and waits that are over. */
    @Nonnull
    public HeldBoosts live(long nowMs) {
        List<BoostRun> running = new ArrayList<>(runs.size());
        for (BoostRun run : runs) {
            if (run.liveAt(nowMs)) {
                running.add(run);
            }
        }
        Map<String, Long> waiting = new HashMap<>();
        for (Map.Entry<String, Long> wait : cooldowns.entrySet()) {
            if (wait.getValue() > nowMs) {
                waiting.put(wait.getKey(), wait.getValue());
            }
        }
        return running.size() == runs.size() && waiting.size() == cooldowns.size() ? this
                : new HeldBoosts(running, waiting);
    }

    /** This record with {@code run} started. */
    @Nonnull
    public HeldBoosts with(@Nonnull BoostRun run) {
        List<BoostRun> next = new ArrayList<>(runs);
        next.add(run);
        return new HeldBoosts(next, cooldowns);
    }

    /** This record with the boost {@code boostId}'s next start waiting until {@code untilMs}. */
    @Nonnull
    public HeldBoosts withCooldown(@Nonnull String boostId, long untilMs) {
        Map<String, Long> next = new HashMap<>(cooldowns);
        next.put(key(boostId), untilMs);
        return new HeldBoosts(runs, next);
    }

    /** This record with none of its own starts left, its waits kept. */
    @Nonnull
    public HeldBoosts withoutRuns() {
        return runs.isEmpty() ? this : new HeldBoosts(List.of(), cooldowns);
    }

    /** When the boost {@code boostId}'s next start may come, in epoch milliseconds; 0 for no wait. */
    public long cooldownUntil(@Nonnull String boostId) {
        Long until = cooldowns.get(key(boostId));
        return until == null ? 0L : until;
    }

    /** How many of its own starts run at {@code nowMs}. */
    public int running(long nowMs) {
        int count = 0;
        for (BoostRun run : runs) {
            if (run.liveAt(nowMs)) {
                count++;
            }
        }
        return count;
    }

    /** How many starts of the boost {@code boostId} run at {@code nowMs}. */
    public int running(@Nonnull String boostId, long nowMs) {
        int count = 0;
        for (BoostRun run : runs) {
            if (run.liveAt(nowMs) && run.is(boostId)) {
                count++;
            }
        }
        return count;
    }

    /** Is this the same as nothing held? */
    public boolean isEmpty() {
        return runs.isEmpty() && cooldowns.isEmpty();
    }

    @Nonnull
    private static String key(@Nonnull String boostId) {
        return boostId.trim().toLowerCase(Locale.ROOT);
    }
}
