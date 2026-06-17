package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;

/**
 * Per-player dialogue-local memory: a small set of string flags read by the
 * generic {@code Flag}/{@code NotFlag} conditions and the once-only reward guard.
 * The engine never persists or inspects these beyond has/set; storage is the
 * consumer's policy (an MMO persists them on a player component, a minigame keeps
 * an in-memory per-round map). Supplied to the engine through a
 * {@link DialogueContext}.
 */
public interface DialogueFlagStore {

    /** True when {@code flag} is currently set for this player. */
    boolean has(@Nonnull String flag);

    /** Set {@code flag} for this player (idempotent). */
    void set(@Nonnull String flag);

    /** A no-op store: every flag reads as unset, sets are dropped (read-only/stateless contexts). */
    DialogueFlagStore NONE = new DialogueFlagStore() {
        @Override public boolean has(@Nonnull String flag) { return false; }
        @Override public void set(@Nonnull String flag) { }
    };
}
