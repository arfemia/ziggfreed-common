package com.ziggfreed.common.world;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One world's resolved selector NAMES, each bound to the {@link MatchRank} of the pattern that
 * earned it. This is the value {@link WorldIdentity} caches per world and the input a
 * {@link WorldSelector} scores a {@code Names} reference against.
 *
 * <p>Names are many-to-many by design: several {@code WorldSelectorAsset}s may contribute
 * patterns to the SAME name, and this index holds the union - each name mapped to the most
 * specific rank any contributing asset achieved. That is what makes two mods each shipping a
 * {@code Zc_Default.json}-ish file safe: the asset id is a pure address, the name is the shared
 * vocabulary, and both contributions apply.
 *
 * <p>Immutable and name-case-insensitive (names are lower-cased on the way in and on lookup).
 * Pure logic, zero engine coupling.
 */
public final class WorldNameIndex {

    /** The index of a world that matched no selector at all. */
    public static final WorldNameIndex EMPTY = new WorldNameIndex(Collections.emptyMap());

    private final Map<String, MatchRank> ranks;

    private WorldNameIndex(@Nonnull Map<String, MatchRank> ranks) {
        this.ranks = ranks;
    }

    /** Build an index from a {@code name -> rank} map (copied, lower-cased, null values dropped). */
    @Nonnull
    public static WorldNameIndex of(@Nonnull Map<String, MatchRank> ranks) {
        Map<String, MatchRank> copy = new LinkedHashMap<>();
        for (Map.Entry<String, MatchRank> e : ranks.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String key = e.getKey().trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            // Keep the most specific contribution when a caller hands in duplicate casings.
            copy.merge(key, e.getValue(), (a, b) -> b.isMoreSpecificThan(a) ? b : a);
        }
        return copy.isEmpty() ? EMPTY : new WorldNameIndex(Collections.unmodifiableMap(copy));
    }

    /** Every selector name this world carries (lower-cased); empty when nothing matched. */
    @Nonnull
    public Set<String> names() {
        return ranks.keySet();
    }

    /** The rank {@code name} matched this world at, or {@code null} when the world lacks it. */
    @Nullable
    public MatchRank rankOf(@Nullable String name) {
        if (name == null) {
            return null;
        }
        return ranks.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Does this world carry {@code name}? */
    public boolean has(@Nullable String name) {
        return rankOf(name) != null;
    }

    public boolean isEmpty() {
        return ranks.isEmpty();
    }
}
