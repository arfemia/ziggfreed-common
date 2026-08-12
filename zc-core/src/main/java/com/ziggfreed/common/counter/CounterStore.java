package com.ziggfreed.common.counter;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * THE persistence seam for per-subject tallies. Everything {@link Counters} knows about a subject
 * lives behind this interface, and it never sees the storage underneath - a component on an entity,
 * a row in a database, a map that dies with the round, all satisfy it identically.
 *
 * <p>The whole of the state is one flat {@code key -> long} map per subject. Grouping (a category
 * per kind of thing being counted) rides in the KEY, built by {@link Counters#key}, so a store never
 * has to learn what a category is and a consumer can add one without a schema change.
 *
 * <p>Implementations are called on whatever thread the consumer counts on and should be cheap;
 * {@link #markDirty} and {@link #flush} exist so a store that batches writes can be told where a
 * transaction boundary is (both no-ops by default).
 */
public interface CounterStore {

    /** This subject's tally under {@code key}, or {@code 0} when nothing is recorded. */
    long get(@Nonnull Subject subject, @Nonnull String key);

    /**
     * Record a tally. A value of {@code 0} REMOVES the key rather than storing a zero, which keeps a
     * reset from leaving an entry behind - the same rule {@link CounterMap} follows.
     */
    void put(@Nonnull Subject subject, @Nonnull String key, long value);

    /** Every tally this subject holds, keyed as stored. Never null. */
    @Nonnull
    Map<String, Long> all(@Nonnull Subject subject);

    /** Forget every tally for this subject. */
    void clear(@Nonnull Subject subject);

    /** Note that this subject's tallies changed, for a store that batches writes. No-op by default. */
    default void markDirty(@Nonnull Subject subject) {
    }

    /** Commit this subject's pending writes now, at a transaction boundary. No-op by default. */
    default void flush(@Nonnull Subject subject) {
    }
}
