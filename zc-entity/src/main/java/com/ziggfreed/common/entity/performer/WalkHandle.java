package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A poll-driven handle over one in-progress performer walk (from {@link StationPerformer#walkTo}).
 * The caller owns the CADENCE: it calls {@link #poll(ComponentAccessor, double)} once per
 * world-thread tick with THIS frame's mutation accessor + the elapsed {@code dtMs} (decision 55 -
 * a {@code CommandBuffer} cannot be captured across frames), and reads the returned {@link State}
 * to know when the walk is done. Both backends poll, but the mechanism underneath differs (the
 * bare-{@code Holder} handle advances the puppet's transform along a solved polyline; the NPC
 * handle measures distance to a marked target the engine's own A* is driving), so arrival/stuck
 * semantics are encapsulated per backend.
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
     * Advance the walk by {@code dtMs} through this frame's {@code accessor} and return the
     * resulting {@link State}. A no-op returning the current terminal state once the walk is
     * {@link #isDone()}; never throws (a lost performer degrades to {@link State#FAILED}).
     */
    @Nonnull
    State poll(@Nonnull ComponentAccessor<EntityStore> accessor, double dtMs);

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
