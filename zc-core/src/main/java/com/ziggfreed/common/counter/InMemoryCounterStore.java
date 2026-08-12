package com.ziggfreed.common.counter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * A complete {@link CounterStore} that keeps everything in memory, keyed by {@link Subject#id()}.
 *
 * <p>Two real uses: unit tests, and any consumer whose tallies are genuinely meant to die with the
 * session (a round, a match, an instance). A consumer that needs them to survive a disconnect writes
 * its own store against the same interface instead.
 *
 * <p>Backed by {@link ConcurrentHashMap} so a count from one thread and a read from another cannot
 * corrupt each other.
 */
public final class InMemoryCounterStore implements CounterStore {

    private final Map<UUID, ConcurrentHashMap<String, Long>> subjects = new ConcurrentHashMap<>();

    @Nonnull
    private ConcurrentHashMap<String, Long> state(@Nonnull Subject subject) {
        return subjects.computeIfAbsent(subject.id(), key -> new ConcurrentHashMap<>());
    }

    @Override
    public long get(@Nonnull Subject subject, @Nonnull String key) {
        Long value = state(subject).get(key);
        return value == null ? 0L : value;
    }

    @Override
    public void put(@Nonnull Subject subject, @Nonnull String key, long value) {
        if (value == 0L) {
            state(subject).remove(key);
        } else {
            state(subject).put(key, value);
        }
    }

    @Override
    @Nonnull
    public Map<String, Long> all(@Nonnull Subject subject) {
        return Map.copyOf(state(subject));
    }

    @Override
    public void clear(@Nonnull Subject subject) {
        state(subject).clear();
    }

    /** Forget one subject entirely (they left, the round ended). */
    public void forget(@Nonnull Subject subject) {
        subjects.remove(subject.id());
    }

    /** Forget everybody. */
    public void clearAll() {
        subjects.clear();
    }
}
