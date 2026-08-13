package com.ziggfreed.common.progress.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Who fires what - the KEYED claim a consumer makes so the library's own generic producers stand
 * down for exactly the ground the consumer covers, and no more.
 *
 * <p><b>A producer claim is keyed by OBJECTIVE KIND</b>, not by a second vocabulary of its own. The
 * kind id is already the shared, normalized, owner-attributed key for "this sort of moment", so a
 * producer stands down per kind rather than a runtime having one global on/off switch. That is what
 * makes double-firing structurally impossible instead of conventionally avoided: a consumer that
 * fires block breaks itself claims that kind, the library's block-break producer returns on its
 * first line, and every other kind keeps working.
 *
 * <p>CONTENT needs no claim of its own: every reader folds the whole shared store and publishes
 * what it folded, and the content layers merge library defaults first, so a consumer's version of
 * an id replaces the library's on rank alone.
 *
 * <p>The register is written at setup and read on hot paths, so reads are lock-free.
 */
final class ProducerClaims {

    /** normalized kind id -> the owner that fires it. */
    private final Map<String, String> kinds = new ConcurrentHashMap<>();

    ProducerClaims() {
    }

    /**
     * Record that {@code owner} fires {@code kindId} itself.
     *
     * @return the owner that already claimed it, or null when this claim was the first
     */
    @Nullable
    String claimKind(@Nullable String kindId, @Nonnull String owner) {
        String key = normalize(kindId);
        if (key == null) {
            return null;
        }
        String previous = kinds.putIfAbsent(key, owner);
        return previous == null || previous.equals(owner) ? null : previous;
    }

    /** Is {@code kindId} still the library default's to fire? */
    boolean defaultProduces(@Nullable String kindId) {
        String key = normalize(kindId);
        return key != null && !kinds.containsKey(key);
    }

    /** Every claimed kind and its owner, sorted by kind, for the boot diagnostic. */
    @Nonnull
    Map<String, String> claimedKinds() {
        return new TreeMap<>(kinds);
    }

    /** How many of {@code candidates} are claimed - the "n of m standing down" half of the log line. */
    int claimedCount(@Nonnull Set<String> candidates) {
        int count = 0;
        for (String candidate : candidates) {
            if (!defaultProduces(candidate)) {
                count++;
            }
        }
        return count;
    }

    void clear() {
        kinds.clear();
    }

    @Nullable
    private static String normalize(@Nullable String id) {
        return id == null || id.isBlank() ? null : id.trim().toLowerCase(Locale.ROOT);
    }
}
