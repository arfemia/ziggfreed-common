package com.ziggfreed.common.commerce;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * Which {@link CommerceStore} this server's commerce state lives in.
 *
 * <p>Producer REPLACEMENT, not layering: a consumer that keeps this state itself installs its own
 * implementation at setup and the previous one stops being asked, so two stores holding two
 * versions of one wallet cannot exist. Until something installs one, the
 * {@link InMemoryCommerceStore} is what answers - shops work and nothing survives a restart, which
 * is the honest degrade rather than a silent half-initialised engine.
 *
 * <p>Read it at CALL time, never cached in a field: a consumer's setup may run after the engine
 * that reads it was built.
 */
public final class CommerceStores {

    private static final AtomicReference<CommerceStore> STORE =
            new AtomicReference<>(new InMemoryCommerceStore());

    private CommerceStores() {
    }

    /** The store in force right now. Never null. */
    @Nonnull
    public static CommerceStore get() {
        return STORE.get();
    }

    /**
     * Install {@code store} as this server's commerce state, replacing whatever answered before.
     * Passing null restores the in-memory default. Call once at setup; the swap is logged, because
     * a server whose purchases stopped persisting should be able to find out why from its boot log.
     */
    public static void install(@Nullable CommerceStore store) {
        CommerceStore next = store != null ? store : new InMemoryCommerceStore();
        CommerceStore previous = STORE.getAndSet(next);
        if (previous != next) {
            SafeLog.info("[commerce] state store is now " + next.getClass().getSimpleName()
                    + " (was " + previous.getClass().getSimpleName() + ")");
        }
    }
}
