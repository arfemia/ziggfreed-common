package com.ziggfreed.common.track.boost;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One start of a timed boost, as it is kept (a player's own on their record, a server one in the server's file):
 * which boost, the percent it lifts by (the boost's own at the start, or what the start asked for), when it started
 * and ends, on the clock, so it runs on while its player is away and across a restart, and the one track the start
 * names, when it named one. Immutable.
 *
 * @param boost     the boost's id as its file spells it
 * @param percent   the percent this start lifts by
 * @param startedAt when it started, in epoch milliseconds
 * @param endsAt    when it ends, in epoch milliseconds
 * @param by        who started it: a player's name, or what paid it (a reward's source, {@code admin})
 * @param track     the one track this start lifts, inside what its boost lifts (a token pointed at one skill of a
 *                  boost that lifts every skill), trimmed; null when it lifts all its boost lifts
 */
public record BoostRun(@Nonnull String boost, double percent, long startedAt, long endsAt, @Nonnull String by,
                       @Nullable String track) {

    public BoostRun {
        boost = boost.trim();
        by = by.trim();
        if (!Double.isFinite(percent)) {
            percent = 0.0;
        }
        track = track == null || track.isBlank() ? null : track.trim();
    }

    /** A start that lifts all its boost lifts. */
    public BoostRun(@Nonnull String boost, double percent, long startedAt, long endsAt, @Nonnull String by) {
        this(boost, percent, startedAt, endsAt, by, null);
    }

    /** Is this start still running at {@code nowMs}? */
    public boolean liveAt(long nowMs) {
        return endsAt > nowMs;
    }

    /** Is it a start of the boost {@code boostId} (any case)? */
    public boolean is(@Nonnull String boostId) {
        return boost.equalsIgnoreCase(boostId.trim());
    }

    /** Does this start let its boost lift {@code trackId} (any case): it names no track, or names that one? */
    public boolean names(@Nonnull String trackId) {
        return track == null || track.equalsIgnoreCase(trackId.trim());
    }

    /** How long it has left at {@code nowMs}, never below zero. */
    public long leftMs(long nowMs) {
        return Math.max(0L, endsAt - nowMs);
    }
}
