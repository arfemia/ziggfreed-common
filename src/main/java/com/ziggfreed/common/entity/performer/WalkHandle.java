package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;

/**
 * A poll-driven handle over one in-progress performer walk (from {@link StationPerformer#walkTo}).
 * The caller owns the CADENCE: it calls {@link #poll(double)} once per world-thread tick with the
 * elapsed {@code dtMs}, and reads the returned {@link State} to know when the walk is done. Both
 * backends poll, but the mechanism underneath differs (the bare-{@code Holder} handle advances the
 * puppet's transform along a solved polyline; the NPC handle measures distance to a marked target
 * the engine's own A* is driving), so arrival/stuck semantics are encapsulated per backend.
 */
public interface WalkHandle {

    /** The state of a walk. Terminal once anything other than {@link #WALKING}. */
    enum State {
        /** Still moving toward the target. */
        WALKING,
        /** Reached the target (or a lenient near-miss for the NPC backend). */
        ARRIVED,
        /** Stopped making progress before arriving (gave up). */
        STUCK,
        /** Could not start / lost the performer (no path, dead ref, etc.). */
        FAILED
    }

    /**
     * Advance the walk by {@code dtMs} and return the resulting {@link State}. A no-op returning the
     * current terminal state once the walk is {@link #isDone()}; never throws (a lost performer
     * degrades to {@link State#FAILED}).
     */
    @Nonnull
    State poll(double dtMs);

    /** The current {@link State} without advancing. */
    @Nonnull
    State state();

    /** Whether the walk has reached a terminal state (anything but {@link State#WALKING}). */
    default boolean isDone() {
        return state() != State.WALKING;
    }

    /** Abandon the walk (stop the puppet / drop the marker); idempotent, safe to call twice. */
    void cancel();
}
