package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;

/**
 * Per-player dialogue STATE: a small set of opaque string keys behind the {@code Once} knobs and
 * the declared {@code Memories} (plus the once-only reward guard). The engine never persists or
 * inspects them beyond has/set/clear, and the keys themselves are engine-internal (see
 * {@link DialogueStateKeys}) - an author never writes one. Storage is the consumer's policy (an
 * MMO persists them on a player component, a minigame keeps an in-memory per-round map). Supplied
 * to the engine through a {@link DialogueContext}.
 */
public interface DialogueFlagStore {

    /** True when {@code flag} is currently set for this player. */
    boolean has(@Nonnull String flag);

    /** Set {@code flag} for this player (idempotent). */
    void set(@Nonnull String flag);

    /**
     * Unset {@code flag} for this player (idempotent). Backs the {@code Forget} action; a store
     * whose backing state cannot drop a key leaves this as the no-op default, and a {@code Forget}
     * against it does nothing.
     */
    default void clear(@Nonnull String flag) { }

    /** A no-op store: every flag reads as unset, writes are dropped (read-only/stateless contexts). */
    DialogueFlagStore NONE = new DialogueFlagStore() {
        @Override public boolean has(@Nonnull String flag) { return false; }
        @Override public void set(@Nonnull String flag) { }
        @Override public void clear(@Nonnull String flag) { }
    };
}
