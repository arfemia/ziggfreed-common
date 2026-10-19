package com.ziggfreed.common.track.boost;

import javax.annotation.Nonnull;

/**
 * One start of a timed boost, as it is kept (a player's own on their record, a server one in the server's file):
 * which boost, the percent it lifts by (the boost's own at the start, or what the start asked for), and when it
 * started and ends, on the clock, so it runs on while its player is away and across a restart. Immutable.
 *
 * @param boost     the boost's id as its file spells it
 * @param percent   the percent this start lifts by
 * @param startedAt when it started, in epoch milliseconds
 * @param endsAt    when it ends, in epoch milliseconds
 * @param by        who started it: a player's name, or what paid it (a reward's source, {@code admin})
 */
public record BoostRun(@Nonnull String boost, double percent, long startedAt, long endsAt, @Nonnull String by) {

    public BoostRun {
        boost = boost.trim();
        by = by.trim();
        if (!Double.isFinite(percent)) {
            percent = 0.0;
        }
    }

    /** Is this start still running at {@code nowMs}? */
    public boolean liveAt(long nowMs) {
        return endsAt > nowMs;
    }

    /** Is it a start of the boost {@code boostId} (any case)? */
    public boolean is(@Nonnull String boostId) {
        return boost.equalsIgnoreCase(boostId.trim());
    }

    /** How long it has left at {@code nowMs}, never below zero. */
    public long leftMs(long nowMs) {
        return Math.max(0L, endsAt - nowMs);
    }
}
