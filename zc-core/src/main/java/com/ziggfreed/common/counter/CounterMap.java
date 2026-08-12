package com.ziggfreed.common.counter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A bag of named long tallies, and nothing else: no owner, no persistence, no engine types. It is
 * the value half of this package, so anything that already knows WHOSE numbers these are (an entry
 * in a table, a row loaded from a file, a per-subject record behind a {@link CounterStore}) can hold
 * one and get the same arithmetic every other holder gets.
 *
 * <p><b>Two ways to move a tally, and they are not interchangeable.</b> {@link #add} accumulates,
 * which is what "how many times has this happened" wants. {@link #highWater} raises a ceiling, which
 * is what "the best they have ever reached" wants - a run of 5 followed by a run of 4 must leave the
 * tally at 5, not 9. Merging two bags has the same two flavours ({@link #mergeSums} and
 * {@link #mergeHighWater}), so an aggregate across several bags keeps the meaning each key had.
 *
 * <p>A key whose value reaches exactly zero is DROPPED rather than stored, so a bag that has been
 * cleared out costs nothing to persist and {@link #isEmpty()} means what it says. Reading an absent
 * key is always {@code 0}, never an error.
 *
 * <p>The backing map is lazily allocated and the field is deliberately plain, so a serializer that
 * walks fields (a JSON store, a file-backed table) round-trips a bag with no adapter.
 *
 * <p>Not thread-safe by itself. A holder that shares one across threads guards it, exactly as it
 * would guard any other mutable value it owns.
 */
public final class CounterMap {

    /** Lazily allocated so an untouched bag carries no map at all. */
    @Nullable private Map<String, Long> values;

    public CounterMap() {
    }

    /** A bag holding a copy of {@code seed}; null, empty, and zero-valued entries are skipped. */
    @Nonnull
    public static CounterMap of(@Nullable Map<String, Long> seed) {
        CounterMap out = new CounterMap();
        out.mergeSums(seed);
        return out;
    }

    /** This tally, or {@code 0} when the key was never touched. */
    public long get(@Nullable String key) {
        if (values == null || key == null) {
            return 0L;
        }
        Long value = values.get(key);
        return value == null ? 0L : value;
    }

    /** Add {@code delta} and return the new tally. A zero delta is a no-op. */
    public long add(@Nullable String key, long delta) {
        if (key == null || key.isBlank() || delta == 0L) {
            return get(key);
        }
        return set(key, get(key) + delta);
    }

    /** Add one and return the new tally. */
    public long increment(@Nullable String key) {
        return add(key, 1L);
    }

    /**
     * Write {@code value} outright and return it. Writing {@code 0} removes the key, which is how a
     * tally is reset without leaving an entry behind.
     */
    public long set(@Nullable String key, long value) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        if (value == 0L) {
            if (values != null) {
                values.remove(key);
            }
            return 0L;
        }
        if (values == null) {
            values = new LinkedHashMap<>();
        }
        values.put(key, value);
        return value;
    }

    /**
     * Raise the tally to {@code value} if that is higher than what is recorded, for a key tracking a
     * BEST rather than a total.
     *
     * @return true only when this call actually raised it
     */
    public boolean highWater(@Nullable String key, long value) {
        if (key == null || key.isBlank() || value <= get(key)) {
            return false;
        }
        set(key, value);
        return true;
    }

    /** Forget one key. Returns true when it was there. */
    public boolean remove(@Nullable String key) {
        return values != null && key != null && values.remove(key) != null;
    }

    /** Forget everything. */
    public void clear() {
        if (values != null) {
            values.clear();
        }
    }

    /** Add every tally in {@code other} to this bag - the aggregate for accumulating keys. */
    public void mergeSums(@Nullable CounterMap other) {
        if (other != null) {
            mergeSums(other.values);
        }
    }

    /** {@link #mergeSums(CounterMap)} from a plain map, for a caller assembling deltas by hand. */
    public void mergeSums(@Nullable Map<String, Long> other) {
        if (other == null || other.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> entry : other.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                add(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Keep the HIGHER of the two tallies for every key - the aggregate for best-ever keys. */
    public void mergeHighWater(@Nullable CounterMap other) {
        if (other == null || other.values == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : other.values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                highWater(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Every tally, as an unmodifiable snapshot in insertion order. */
    @Nonnull
    public Map<String, Long> all() {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    /** Every key that currently holds a tally. */
    @Nonnull
    public Set<String> keys() {
        return values == null ? Set.of() : Set.copyOf(values.keySet());
    }

    /** How many keys hold a tally. */
    public int size() {
        return values == null ? 0 : values.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** An independent copy, for a caller handing a bag out without handing over the ability to edit it. */
    @Nonnull
    public CounterMap copy() {
        return of(values);
    }

    @Override
    public String toString() {
        return "CounterMap" + all();
    }
}
