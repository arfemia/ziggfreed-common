package com.ziggfreed.common.counter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * Named long tallies per subject: how many times a thing has happened, and the best a subject ever
 * reached. Built over a {@link CounterStore}, which is the only thing here that knows where the
 * numbers live.
 *
 * <p><b>Plain keys and CATEGORY keys, one flat store.</b> A plain key is a running total for the
 * whole subject. A category key groups a total by what it happened to ({@code ("broken", "stone")}),
 * which is the shape a "per thing" breakdown takes beside its grand total. Both live in one map:
 * {@link #key} joins a category and a name with {@value #CATEGORY_SEPARATOR}, so a store needs no
 * schema for a category and a consumer adds one without migrating anything. That separator is
 * therefore RESERVED inside a category or a name - see {@link #isReservedName}.
 *
 * <p><b>Two ways to move a tally.</b> {@link #add} accumulates; {@link #highWater} raises a ceiling.
 * Picking the wrong one is the one way to corrupt a count, so the choice belongs to whoever knows
 * what the number MEANS, never to the call site that happens to have a value in hand. The same two
 * flavours are on {@link CounterMap} for aggregates.
 *
 * <p><b>No domain vocabulary lives here.</b> This class counts; it has no idea what is being counted
 * and never will. What each key means, which are totals and which are bests, and how they are named
 * are the consumer's business. (Item-carried values that feed a native stat channel are a different
 * thing entirely and belong to the entity stats bridge, not to a tally.)
 *
 * <p>Thread safety is the store's. The default store is safe; the arithmetic here is
 * read-modify-write, so a consumer counting the same key from two threads serializes it.
 */
public final class Counters {

    /** Joins a category to a name inside one flat key. Reserved in both halves. */
    public static final String CATEGORY_SEPARATOR = "/";

    private final CounterStore store;

    /** Counters over a store that dies with the process - a test, or a session-scoped consumer. */
    public Counters() {
        this(new InMemoryCounterStore());
    }

    public Counters(@Nonnull CounterStore store) {
        this.store = store;
    }

    /** The persistence seam behind these counters, for a consumer that needs to reach it directly. */
    @Nonnull
    public CounterStore store() {
        return store;
    }

    // ==================== Plain keys ====================

    /** This subject's tally, or {@code 0}. */
    public long get(@Nonnull Subject subject, @Nonnull String key) {
        return store.get(subject, key);
    }

    /** Add {@code delta} and return the new tally. A zero delta touches nothing. */
    public long add(@Nonnull Subject subject, @Nonnull String key, long delta) {
        if (delta == 0L || key.isBlank()) {
            return get(subject, key);
        }
        long next = store.get(subject, key) + delta;
        store.put(subject, key, next);
        store.markDirty(subject);
        return next;
    }

    /** Add one and return the new tally. */
    public long increment(@Nonnull Subject subject, @Nonnull String key) {
        return add(subject, key, 1L);
    }

    /** Write a tally outright; {@code 0} clears it. */
    public void set(@Nonnull Subject subject, @Nonnull String key, long value) {
        if (key.isBlank()) {
            return;
        }
        store.put(subject, key, value);
        store.markDirty(subject);
    }

    /**
     * Raise the tally to {@code value} if that beats what is recorded, for a key tracking a BEST
     * rather than a total.
     *
     * @return true only when this call raised it
     */
    public boolean highWater(@Nonnull Subject subject, @Nonnull String key, long value) {
        if (key.isBlank() || value <= store.get(subject, key)) {
            return false;
        }
        store.put(subject, key, value);
        store.markDirty(subject);
        return true;
    }

    // ==================== Category keys ====================

    /** The flat key a category and a name resolve to. A blank category yields the bare name. */
    @Nonnull
    public static String key(@Nullable String category, @Nonnull String name) {
        return category == null || category.isBlank() ? name : category + CATEGORY_SEPARATOR + name;
    }

    /**
     * Does {@code name} contain the character a category key reserves, making it unusable as a
     * category or a name? A validator calls this so a bad name is a load-time finding rather than a
     * tally quietly filed under the wrong category later.
     */
    public static boolean isReservedName(@Nullable String name) {
        return name == null || name.isBlank() || name.contains(CATEGORY_SEPARATOR);
    }

    /** One tally inside a category. */
    public long get(@Nonnull Subject subject, @Nonnull String category, @Nonnull String name) {
        return get(subject, key(category, name));
    }

    /** Add inside a category and return the new tally. */
    public long add(@Nonnull Subject subject, @Nonnull String category, @Nonnull String name, long delta) {
        return add(subject, key(category, name), delta);
    }

    /** Add one inside a category and return the new tally. */
    public long increment(@Nonnull Subject subject, @Nonnull String category, @Nonnull String name) {
        return add(subject, key(category, name), 1L);
    }

    /** Raise a ceiling inside a category. */
    public boolean highWater(@Nonnull Subject subject, @Nonnull String category, @Nonnull String name,
                             long value) {
        return highWater(subject, key(category, name), value);
    }

    /**
     * One category's whole breakdown, keyed by the NAME half (the category prefix is stripped), in
     * key order. Empty when the subject has counted nothing in it.
     */
    @Nonnull
    public Map<String, Long> category(@Nonnull Subject subject, @Nonnull String category) {
        String prefix = category + CATEGORY_SEPARATOR;
        Map<String, Long> stored = store.all(subject);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String key : new TreeSet<>(stored.keySet())) {
            if (key.startsWith(prefix)) {
                out.put(key.substring(prefix.length()), stored.get(key));
            }
        }
        return out;
    }

    /** Every category this subject has counted in, sorted. */
    @Nonnull
    public Set<String> categories(@Nonnull Subject subject) {
        Set<String> out = new TreeSet<>();
        for (String key : store.all(subject).keySet()) {
            int sep = key.indexOf(CATEGORY_SEPARATOR);
            if (sep > 0) {
                out.add(key.substring(0, sep));
            }
        }
        return out;
    }

    // ==================== Whole-subject views ====================

    /** Every tally this subject holds, keyed exactly as stored (category keys included). */
    @Nonnull
    public Map<String, Long> all(@Nonnull Subject subject) {
        return store.all(subject);
    }

    /** Every tally as a {@link CounterMap}, for merging into an aggregate. */
    @Nonnull
    public CounterMap snapshot(@Nonnull Subject subject) {
        return CounterMap.of(store.all(subject));
    }

    /** Only the PLAIN keys (no category), keyed as stored, in key order. */
    @Nonnull
    public Map<String, Long> totals(@Nonnull Subject subject) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String key : new TreeSet<>(store.all(subject).keySet())) {
            if (!key.contains(CATEGORY_SEPARATOR)) {
                out.put(key, store.get(subject, key));
            }
        }
        return out;
    }

    /** Apply a whole bag of deltas at once, accumulating each. */
    public void addAll(@Nonnull Subject subject, @Nullable Map<String, Long> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> entry : deltas.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                add(subject, entry.getKey(), entry.getValue());
            }
        }
    }

    /** Forget every tally this subject holds. */
    public void clear(@Nonnull Subject subject) {
        store.clear(subject);
        store.markDirty(subject);
    }

    /** Commit this subject's pending writes, at a transaction boundary. */
    public void flush(@Nonnull Subject subject) {
        store.flush(subject);
    }
}
