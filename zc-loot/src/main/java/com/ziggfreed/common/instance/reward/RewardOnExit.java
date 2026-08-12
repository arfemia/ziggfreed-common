package com.ziggfreed.common.instance.reward;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * When an instance's authored reward list is granted on exit: never, only on a win, or
 * always. A generic instance policy (not game-specific), so it lives in common; the
 * consumer reads it at its resolve/teardown choke-point and runs the grant pass.
 */
public enum RewardOnExit {

    /** Grant nothing on exit (rewards, if any, flow through another path). */
    NONE,

    /** Grant the reward list only when the run was won. */
    ON_WIN,

    /** Grant the reward list on every exit (win or loss). */
    ALWAYS;

    /** Parse a codec/string value; unknown / null falls back to {@link #NONE}. */
    public static RewardOnExit fromString(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /** Whether the reward list should be granted for a run with this {@code win} outcome. */
    public boolean grantsOn(boolean win) {
        return this == ALWAYS || (this == ON_WIN && win);
    }
}
