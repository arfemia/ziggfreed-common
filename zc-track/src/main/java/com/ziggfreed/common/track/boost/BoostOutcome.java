package com.ziggfreed.common.track.boost;

/** What asking a boost to start came to ({@link Boosts#start}). */
public enum BoostOutcome {
    /** It started. */
    STARTED,
    /** No such boost on this server: unknown, switched off or gated out. */
    UNKNOWN,
    /** A standing boost, which runs on its own and is never started. */
    STANDING,
    /** A boost with no length, from its file or the start. */
    UNTIMED,
    /** Its calendar event is not running, so it is hidden. */
    OUT_OF_SEASON,
    /** The player does not meet its {@code Requires}. */
    LOCKED,
    /** The player is not live here, or their record could not be read or written. */
    NOT_LIVE,
    /** The player started it too recently ({@code Stack.Cooldown}). */
    COOLDOWN,
    /** As many of it run as its {@code Stack.Max} allows. */
    STACK_FULL,
    /** The player already runs as many personal boosts as the server allows. */
    PERSONAL_FULL,
    /** The server already runs as many server boosts as it allows. */
    SERVER_FULL;

    /** Could it start later, as it is (a wait, a limit, a season, a requirement), rather than never here? */
    public boolean couldStartLater() {
        return this == OUT_OF_SEASON || this == LOCKED || this == COOLDOWN || this == STACK_FULL
                || this == PERSONAL_FULL || this == SERVER_FULL;
    }
}
