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
 * <p>One {@link CounterMap} per subject, guarded per bag, so a count from one thread and a read from
 * another cannot corrupt each other, and every key rule the bag has (a key matched without regard to
 * case, a zero dropped rather than stored) holds here exactly as it does in a persisted store.
 */
public final class InMemoryCounterStore implements CounterStore {

    private final Map<UUID, CounterMap> subjects = new ConcurrentHashMap<>();

    @Nonnull
    private CounterMap bag(@Nonnull Subject subject) {
        return subjects.computeIfAbsent(subject.id(), key -> new CounterMap());
    }

    @Override
    public long get(@Nonnull Subject subject, @Nonnull String key) {
        CounterMap bag = bag(subject);
        synchronized (bag) {
            return bag.get(key);
        }
    }

    @Override
    public void put(@Nonnull Subject subject, @Nonnull String key, long value) {
        CounterMap bag = bag(subject);
        synchronized (bag) {
            bag.set(key, value);
        }
    }

    @Override
    @Nonnull
    public Map<String, Long> all(@Nonnull Subject subject) {
        CounterMap bag = bag(subject);
        synchronized (bag) {
            return bag.all();
        }
    }

    @Override
    public void clear(@Nonnull Subject subject) {
        CounterMap bag = bag(subject);
        synchronized (bag) {
            bag.clear();
        }
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
