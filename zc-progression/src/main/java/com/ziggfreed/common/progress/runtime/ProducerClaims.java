package com.ziggfreed.common.progress.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Who fires what, and who owns which content namespace - the two KEYED claims a consumer makes so
 * the library's own generic parts stand down for exactly the ground the consumer covers, and no
 * more.
 *
 * <p><b>A producer claim is keyed by OBJECTIVE KIND</b>, not by a second vocabulary of its own. The
 * kind id is already the shared, normalized, owner-attributed key for "this sort of moment", so a
 * producer stands down per kind rather than a runtime having one global on/off switch. That is what
 * makes double-firing structurally impossible instead of conventionally avoided: a consumer that
 * fires block breaks itself claims that kind, the library's block-break producer returns on its
 * first line, and every other kind keeps working.
 *
 * <p><b>A content claim is keyed by NAMESPACE.</b> A consumer that folds a namespace's content into
 * the runtime ITSELF - usually because it converts it into something richer than the shared schema
 * carries - claims that namespace, and the library's own default source drops every definition it
 * owns. One claim covers both content kinds, because a namespace belongs to whoever authored it
 * rather than to one sort of content.
 *
 * <p>Both registers are written at setup and read on hot paths, so both are lock-free reads.
 */
final class ProducerClaims {

    /** normalized kind id -> the owner that fires it. */
    private final Map<String, String> kinds = new ConcurrentHashMap<>();

    /** normalized namespace -> the owner that folds it. */
    private final Map<String, String> namespaces = new ConcurrentHashMap<>();

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

    /**
     * Record that {@code owner} folds {@code namespace}'s content itself.
     *
     * @return true when this claim was new, so a caller knows to republish
     */
    boolean claimNamespace(@Nullable String namespace, @Nonnull String owner) {
        String key = normalize(namespace);
        return key != null && namespaces.putIfAbsent(key, owner) == null;
    }

    /** Has somebody claimed {@code namespace}? */
    boolean ownsNamespace(@Nullable String namespace) {
        String key = normalize(namespace);
        return key != null && namespaces.containsKey(key);
    }

    /** Every claimed kind and its owner, sorted by kind, for the boot diagnostic. */
    @Nonnull
    Map<String, String> claimedKinds() {
        return new TreeMap<>(kinds);
    }

    /** Every claimed namespace, sorted, for the boot diagnostic. */
    @Nonnull
    List<String> claimedNamespaces() {
        List<String> out = new ArrayList<>(namespaces.keySet());
        out.sort(String::compareTo);
        return List.copyOf(out);
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
        namespaces.clear();
    }

    @Nullable
    private static String normalize(@Nullable String id) {
        return id == null || id.isBlank() ? null : id.trim().toLowerCase(Locale.ROOT);
    }
}
