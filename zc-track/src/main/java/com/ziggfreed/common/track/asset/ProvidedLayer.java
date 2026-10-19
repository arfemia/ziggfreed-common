package com.ziggfreed.common.track.asset;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * What modules derive in code from their own assets and hand a track store (a reputation's track and curve,
 * read off its native group and its companion file), kept per owner and given to the config's defaults layer as
 * one union. That layer sits under the packs' files and the owner's word, so a pack's file of the same id still
 * replaces an entry whole and an owner entry retunes it leaf by leaf, decoded against it. Two owners providing
 * one id: the first owner to provide it keeps it.
 *
 * @param <T> the store's asset type
 */
final class ProvidedLayer<T> {

    /** Lower-cased owner to its entries, in the order the owners first provided. */
    private final Map<String, Map<String, T>> byOwner = new LinkedHashMap<>();

    /**
     * Replace what {@code owner} provides with {@code entries} (empty withdraws them all) and answer the union
     * every owner provides now, ids lower-cased.
     */
    @Nonnull
    synchronized Map<String, T> provide(@Nonnull String owner, @Nonnull Map<String, T> entries) {
        String key = owner.trim().toLowerCase(Locale.ROOT);
        if (entries.isEmpty()) {
            byOwner.remove(key);
        } else {
            byOwner.put(key, new LinkedHashMap<>(entries));
        }
        Map<String, T> union = new LinkedHashMap<>();
        for (Map<String, T> provided : byOwner.values()) {
            for (Map.Entry<String, T> entry : provided.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    union.putIfAbsent(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }
        return union;
    }
}
