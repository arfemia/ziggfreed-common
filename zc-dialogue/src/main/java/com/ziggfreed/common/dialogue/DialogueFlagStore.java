package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;

/**
 * ONE BACKEND behind per-player dialogue STATE: a small set of opaque string keys carrying the
 * {@code Once} knobs, the declared {@code Memories} and the once-only reward guard. The engine
 * never inspects them beyond has/set/clear, and the keys themselves are engine-internal (see
 * {@link DialogueStateKeys}) - an author never writes one.
 *
 * <p><b>A consumer does not implement this.</b> The library ships both backends a declared lifetime
 * can name - {@link InMemoryDialogueFlagStore} for {@code Session} and the progress-component one
 * for everything else - and {@link DialogueMemories} routes each key to the right one by what its
 * author declared. A consumer implementing a third would be answering for a lifetime nothing can
 * declare, which is how one authored word came to mean "survives a restart" in one mod and "gone at
 * round exit" in another.
 *
 * <p>Every method beyond {@link #has} and {@link #set} has a no-op default, because a backend whose
 * state genuinely cannot drop a key should say so by inheriting rather than by pretending. Both
 * shipped backends implement all four.
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

    /**
     * Drop every key filed under a leading {@code prefix}. This is what a declared
     * {@code ResetWithQuest} lifetime is made of: the memory is filed inside its quest's own
     * namespace, and resetting that quest drops the namespace whole.
     *
     * <p>The prefix arrives in its UN-prefixed form ({@code q:<questId>:}); applying the session
     * namespace on top of it is {@link DialogueMemories}'s job, not a backend's.
     */
    default void clearWithPrefix(@Nonnull String prefix) { }

    /** Drop everything this backend holds for the player (an administrator's start-over). */
    default void clearAll() { }

    /** A no-op store: every flag reads as unset, writes are dropped (read-only/stateless contexts). */
    DialogueFlagStore NONE = new DialogueFlagStore() {
        @Override public boolean has(@Nonnull String flag) { return false; }
        @Override public void set(@Nonnull String flag) { }
        @Override public void clear(@Nonnull String flag) { }
    };
}
