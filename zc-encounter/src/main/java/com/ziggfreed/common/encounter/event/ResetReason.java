package com.ziggfreed.common.encounter.event;

/** Why a run ended; carried by {@link EncounterResetEvent}, always the last event of a run. */
public enum ResetReason {
    /** The script re-armed itself with a {@code zc:reset} beat. */
    RESET_SIGNAL,
    /** The encounter entity was removed (a despawn, an admin end, a cleanup). */
    REMOVED,
    /** A builder hot-reload rebuilt the encounter, which restarts it at its start state. */
    RELOADED,
    /** The run outlived the binding's {@code MaxRunSeconds}. */
    TIMEOUT,
    /** The encounter's chunk or world unloaded under it. */
    WORLD_UNLOAD
}
