package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;

/**
 * A moment fired for SEVERAL subjects at once as one shared credit: a party's boss kill, dispatched
 * once per participant inside the same tick. A producer whose payload carries this says "these
 * fires are one event", and the one thing that reads it today is the server-first arbitration: a
 * claim one of the credited subjects wins under a credit key is a win for every other subject whose
 * fire carries the same key, so a five-player world first pays five players instead of one winner
 * and four lost-the-race toasts.
 *
 * <p>The key is a RUN identity, never a time window: two unrelated parties finishing the same fight
 * seconds apart carry two keys and race exactly as before. A re-test with no key (the login sweep,
 * a scripted grant) never co-claims anything.
 */
public interface SharedCredit extends MomentPayload {

    /** The identity every fire of this one event shares; blank means no shared credit. */
    @Nonnull
    String creditKey();
}
