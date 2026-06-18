package com.ziggfreed.common.lobby;

/**
 * The outcome of a {@link MatchmakingQueue#join} attempt, so a caller (command, NPC
 * dialogue action) can give the right feedback without inspecting queue internals.
 */
public enum JoinResult {

    /** The player was added to the queue. */
    JOINED,

    /** The player is already in this (or another) queue. */
    ALREADY_QUEUED,

    /** The player is already in an active session (the injected {@code alreadyEngaged} predicate). */
    ALREADY_ENGAGED,

    /** The queue is mid-launch and cannot accept a join right now. */
    QUEUE_UNAVAILABLE;

    /** True only for {@link #JOINED}. */
    public boolean ok() {
        return this == JOINED;
    }
}
