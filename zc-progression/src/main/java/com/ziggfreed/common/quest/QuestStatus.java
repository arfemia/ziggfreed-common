package com.ziggfreed.common.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A quest's recorded state for one player.
 *
 * <p><b>What is STORED is not always what a surface should SHOW.</b> A repeatable quest sits in
 * {@link #COMPLETED} forever once finished, yet it becomes offerable again the moment its cooldown
 * elapses. Every player-facing read therefore goes through
 * {@link QuestLifecycle#effectiveStatus}, which turns that stored {@code COMPLETED} into
 * {@link #ON_COOLDOWN} or {@link #NOT_STARTED} as the clock decides. Painting the raw stored value
 * is what makes a finished daily read "Completed" forever.
 */
public enum QuestStatus {

    /** Never accepted, or reset back to offerable. */
    NOT_STARTED,

    /** Accepted and being worked on. */
    ACTIVE,

    /** Finished and paid out. Terminal for a one-shot quest; re-offerable for a repeatable. */
    COMPLETED,

    /** Objectives all met, rewards deliberately NOT granted yet - waiting on the player to claim. */
    COMPLETED_UNCLAIMED,

    /**
     * An EFFECTIVE-only state: never written to a store, only ever returned by
     * {@link QuestLifecycle#effectiveStatus} for a finished repeatable whose cooldown is still
     * running.
     */
    ON_COOLDOWN;

    /** Parse a case-insensitive name, falling back to {@link #NOT_STARTED} for null/unknown input. */
    @Nonnull
    public static QuestStatus fromString(@Nullable String name) {
        if (name == null) {
            return NOT_STARTED;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NOT_STARTED;
        }
    }
}
