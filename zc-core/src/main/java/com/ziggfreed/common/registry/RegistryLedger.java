package com.ziggfreed.common.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The one registration bookkeeping engine every OPEN registry in this library delegates to: a
 * normalized id maps to a value, the owner that registered it, and a running failure count plus
 * its most recent failure message. Any registry that lets a third party claim an id - a factor
 * provider, an interaction handler, an anchor resolver - keeps its map here rather than growing
 * its own {@code ConcurrentHashMap} plus a parallel warn-once set, so "who owns this id, and how
 * often has it failed" is answered the same way for every registry an admin command or a
 * validator reads.
 *
 * <p><b>Identity, not equality, decides an overwrite warning.</b> A consumer re-running its own
 * {@code setup()} (a hot reload, a second plugin load in a test harness) calls {@code put} again
 * with the SAME handler/provider instance, and that must stay silent - only replacing one
 * DISTINCT instance with another is worth a log line, and only once per id so a flapping
 * re-register can never spam the log.
 *
 * <p>Every write is {@code ConcurrentHashMap}-backed (lock-free reads); ids are matched
 * case-insensitively via {@link String#trim()} + {@link Locale#ROOT} lower-casing. Every
 * registered value keeps its own failure counter, so a broken third-party handler is countable
 * without a second map.
 *
 * @param <T> the registered value type (a handler, a provider, a resolver)
 */
public class RegistryLedger<T> {

    /** The owner name a caller did not attribute a registration to. */
    public static final String UNATTRIBUTED = "unattributed";

    /** The log-line prefix a ledger uses when it was constructed without one of its own. */
    private static final String DEFAULT_LABEL = "registry";

    /** A ledger entry snapshot: who owns {@code id}, and its failure history. */
    public record RegistrationInfo(@Nonnull String owner, long failures, @Nullable String lastFailure) {
    }

    private static final class Entry<T> {
        private final T value;
        private final String owner;
        private final AtomicLong failures = new AtomicLong();
        private volatile String lastFailure;

        private Entry(@Nonnull T value, @Nonnull String owner) {
            this.value = value;
            this.owner = owner;
        }
    }

    private final Map<String, Entry<T>> entries = new ConcurrentHashMap<>();
    private final Set<String> overwriteWarned = ConcurrentHashMap.newKeySet();
    private final String label;

    public RegistryLedger() {
        this(DEFAULT_LABEL);
    }

    /**
     * A ledger whose log lines are prefixed {@code [label]}, so a server owner reading an
     * overwrite warning can tell which registry it came from.
     */
    public RegistryLedger(@Nullable String label) {
        this.label = label == null || label.isBlank() ? DEFAULT_LABEL : label.trim();
    }

    /**
     * Register (or replace) {@code id}'s value, attributed to {@code owner} ({@link
     * #UNATTRIBUTED} when {@code null}/blank). A blank id or a null value is ignored. Replacing a
     * DIFFERENT value instance for an id already registered warns once, naming the previous and
     * the new owner; replacing with the SAME instance (by identity) is silent and keeps the
     * existing failure history.
     */
    public void put(@Nullable String id, @Nullable String owner, @Nullable T value) {
        if (id == null || id.isBlank() || value == null) {
            return;
        }
        String key = normalize(id);
        String ownerName = ownerOrUnattributed(owner);
        Entry<T> previous = entries.get(key);
        if (previous != null && previous.value == value) {
            // Idempotent re-registration: keep the existing entry (and its failure history) as-is.
            return;
        }
        if (previous != null && overwriteWarned.add(key)) {
            SafeLog.warn("[" + label + "] '" + key + "' was registered by '" + previous.owner
                    + "' and is now overwritten by '" + ownerName + "'");
        }
        entries.put(key, new Entry<>(value, ownerName));
    }

    /** The value registered for {@code id}, or {@code null} when nothing is. */
    @Nullable
    public T get(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Entry<T> entry = entries.get(normalize(id));
        return entry == null ? null : entry.value;
    }

    /** Is {@code id} registered? */
    public boolean isRegistered(@Nullable String id) {
        return id != null && !id.isBlank() && entries.containsKey(normalize(id));
    }

    /** Every registered id, sorted (diagnostics, an authoring dropdown, a validator hint). */
    @Nonnull
    public Set<String> ids() {
        return Collections.unmodifiableSet(new TreeSet<>(entries.keySet()));
    }

    /**
     * Record that {@code id}'s registered value just failed (threw, or otherwise misbehaved).
     * A no-op when {@code id} is not registered - a failure only counts against an owner that
     * exists to be blamed.
     */
    public void recordFailure(@Nullable String id, @Nullable String message) {
        if (id == null || id.isBlank()) {
            return;
        }
        Entry<T> entry = entries.get(normalize(id));
        if (entry == null) {
            return;
        }
        entry.failures.incrementAndGet();
        entry.lastFailure = message;
    }

    /** A fresh snapshot of every entry's owner + failure history, keyed by normalized id. */
    @Nonnull
    public Map<String, RegistrationInfo> info() {
        Map<String, RegistrationInfo> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry<T>> e : entries.entrySet()) {
            Entry<T> entry = e.getValue();
            out.put(e.getKey(), new RegistrationInfo(entry.owner, entry.failures.get(), entry.lastFailure));
        }
        return out;
    }

    /** Drop every registration. A live server registers once and never unregisters; tests reset. */
    public void clear() {
        entries.clear();
        overwriteWarned.clear();
    }

    /** The one id normalization every registry built on this ledger shares. */
    @Nonnull
    public static String normalize(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String ownerOrUnattributed(@Nullable String owner) {
        return owner == null || owner.isBlank() ? UNATTRIBUTED : owner.trim();
    }
}
