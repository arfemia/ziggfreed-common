package com.ziggfreed.common.track.event;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.track.TrackChange;
import com.ziggfreed.common.track.TrackKey;

/**
 * A climb moved through zc (a gain, a loss, a prestige): fired once per change, on the subject's own thread,
 * after the record was kept and its unlocks parked, at what actually landed. Levels are the current climb's
 * before and after; the best levels are what every gate reads, and a prestige lowers the level while the best
 * stays. A change that moved nothing fires none.
 */
public final class ZigTrackChangedEvent implements IEvent<Void> {

    private final String subjectKind;
    private final String subjectId;
    @Nullable private final UUID playerId;
    private final String namespace;
    private final String trackId;
    private final String run;
    private final long valueBefore;
    private final long valueAfter;
    private final int levelBefore;
    private final int levelAfter;
    private final int bestBefore;
    private final int bestAfter;
    private final int prestigeBefore;
    private final int prestigeAfter;
    private final int claims;
    private final String source;
    private final boolean startedEmpty;
    private final double asked;

    public ZigTrackChangedEvent(@Nonnull String subjectKind, @Nonnull String subjectId, @Nullable UUID playerId,
            @Nonnull String namespace, @Nonnull String trackId, @Nonnull String run, long valueBefore, long valueAfter,
            int levelBefore, int levelAfter, int bestBefore, int bestAfter, int prestigeBefore, int prestigeAfter,
            int claims, @Nonnull String source, boolean startedEmpty, double asked) {
        this.subjectKind = subjectKind;
        this.subjectId = subjectId;
        this.playerId = playerId;
        this.namespace = namespace;
        this.trackId = trackId;
        this.run = run;
        this.valueBefore = valueBefore;
        this.valueAfter = valueAfter;
        this.levelBefore = levelBefore;
        this.levelAfter = levelAfter;
        this.bestBefore = bestBefore;
        this.bestAfter = bestAfter;
        this.prestigeBefore = prestigeBefore;
        this.prestigeAfter = prestigeAfter;
        this.claims = claims;
        this.source = source;
        this.startedEmpty = startedEmpty;
        this.asked = asked;
    }

    /** The event for {@code change}, saying nothing of what was asked. */
    @Nonnull
    public static ZigTrackChangedEvent of(@Nonnull TrackChange change) {
        return of(change, Double.NaN);
    }

    /** The event for {@code change}, made by a write that asked for {@code asked} ({@link #asked()}). */
    @Nonnull
    public static ZigTrackChangedEvent of(@Nonnull TrackChange change, double asked) {
        TrackKey key = change.key();
        return new ZigTrackChangedEvent(key.subject().kind(), key.subject().id(), key.playerId(), key.namespace(),
                key.track(), key.run(), change.before().value(), change.after().value(), change.levelBefore(),
                change.levelAfter(), change.bestBefore(), change.bestAfter(), change.before().prestige(),
                change.after().prestige(), change.claims(), change.source(), change.before().isEmpty(), asked);
    }

    /** The kind of subject that climbs ({@code player} today). */
    @Nonnull
    public String subjectKind() {
        return subjectKind;
    }

    /** The subject's id within its kind. */
    @Nonnull
    public String subjectId() {
        return subjectId;
    }

    /** The player who climbs, or null for another kind of subject. */
    @Nullable
    public UUID playerId() {
        return playerId;
    }

    /** The ruleset the climb belongs to, {@code ""} for the default one. */
    @Nonnull
    public String namespace() {
        return namespace;
    }

    /** The track id, lower-cased. */
    @Nonnull
    public String trackId() {
        return trackId;
    }

    /** The seasonal run, {@code ""} for none. */
    @Nonnull
    public String run() {
        return run;
    }

    /** Is this the default climb (no namespace, no run)? */
    public boolean isDefaultClimb() {
        return namespace.isEmpty() && run.isEmpty();
    }

    public long valueBefore() {
        return valueBefore;
    }

    public long valueAfter() {
        return valueAfter;
    }

    public int levelBefore() {
        return levelBefore;
    }

    public int levelAfter() {
        return levelAfter;
    }

    public int bestBefore() {
        return bestBefore;
    }

    public int bestAfter() {
        return bestAfter;
    }

    public int prestigeBefore() {
        return prestigeBefore;
    }

    public int prestigeAfter() {
        return prestigeAfter;
    }

    /** How many unlock Claims this change parked. */
    public int claims() {
        return claims;
    }

    /** What moved it: a reward's source id, {@code admin}, {@code prestige}. */
    @Nonnull
    public String source() {
        return source;
    }

    /**
     * Was nothing on record before this move, so it was the first move of a fresh climb, which starts at 0? A module
     * whose climbs start somewhere else (a reputation's starting value) puts its start under such a move.
     */
    public boolean startedEmpty() {
        return startedEmpty;
    }

    /**
     * What the write asked for before any gain factor: a gain's ask (a summed source's whole sum), a loss as a
     * negative, an exact set as how far it moved the value; NaN for a prestige or when the firer did not say.
     */
    public double asked() {
        return asked;
    }
}
