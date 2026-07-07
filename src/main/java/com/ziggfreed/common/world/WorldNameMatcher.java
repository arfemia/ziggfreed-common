package com.ziggfreed.common.world;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the best-matching per-world payload for a world name - the generic world-name
 * selector shared by every consumer with "per-world config" semantics (the MMO's world
 * rules, the mob-scaling mod's per-world settings, a future minigame's world policies).
 * Instance worlds spawn with a random suffix ({@code instance-dungeon_i_asdf334rf}), so
 * matching supports a trailing-{@code *} prefix.
 *
 * <p>Precedence (most specific wins):
 * <ol>
 *   <li><b>exact</b> - the pattern equals the world name (case-insensitive)</li>
 *   <li><b>longest prefix</b> - the pattern ends in {@code *}; the longest matching prefix
 *       wins (so {@code dungeon_ii*} beats {@code dungeon_i*} for {@code dungeon_ii}, and
 *       the bare {@code dungeon_i} world only matches {@code dungeon_i*} since the
 *       {@code _ii}/{@code _iii} prefixes are longer than it)</li>
 *   <li><b>bare {@code *}</b> - the catch-all</li>
 *   <li>{@code null} - nothing matched (the caller falls back to its global settings)</li>
 * </ol>
 *
 * <p>Each {@link Entry} pre-parses its pattern once at config-load time so per-lookup cost
 * is a string compare / {@code startsWith}; a consumer caches the resolution per world.
 * Pure logic, zero engine coupling.
 */
public final class WorldNameMatcher {

    private WorldNameMatcher() {
    }

    /** One authored rule: a pre-parsed match pattern bound to its payload. */
    public static final class Entry<T> {
        /** Raw pattern as authored, for diagnostics / validation (e.g. {@code "dungeon_i*"}). */
        @Nonnull
        public final String pattern;
        private final String normalized;   // lower-cased pattern
        private final boolean wildcardAll; // pattern == "*"
        private final boolean prefix;      // pattern ended in "*" (and was not just "*")
        private final String prefixLower;  // normalized minus the trailing "*"; "" when exact
        @Nonnull
        public final T payload;

        public Entry(@Nonnull String pattern, @Nonnull T payload) {
            this.pattern = pattern;
            this.payload = payload;
            String p = pattern.trim();
            this.normalized = p.toLowerCase(Locale.ROOT);
            if (p.equals("*")) {
                this.wildcardAll = true;
                this.prefix = false;
                this.prefixLower = "";
            } else if (p.endsWith("*")) {
                this.wildcardAll = false;
                this.prefix = true;
                this.prefixLower = normalized.substring(0, normalized.length() - 1);
            } else {
                this.wildcardAll = false;
                this.prefix = false;
                this.prefixLower = "";
            }
        }

        boolean isDefaultRule() {
            return wildcardAll;
        }

        boolean matchesExact(@Nonnull String worldLower) {
            return !wildcardAll && !prefix && normalized.equals(worldLower);
        }

        /** The matched prefix length (for longest-wins), or -1 if no prefix match. */
        int prefixMatchLength(@Nonnull String worldLower) {
            return (prefix && worldLower.startsWith(prefixLower)) ? prefixLower.length() : -1;
        }
    }

    /**
     * Resolve the best-matching payload for {@code worldName} against the authored
     * {@code entries}. Returns {@code null} when {@code worldName} is null/blank,
     * {@code entries} is empty, or nothing matches (the caller then uses its GLOBAL settings).
     */
    @Nullable
    public static <T> T resolve(@Nonnull List<Entry<T>> entries, @Nullable String worldName) {
        if (worldName == null || worldName.isEmpty() || entries.isEmpty()) {
            return null;
        }
        String worldLower = worldName.toLowerCase(Locale.ROOT);

        Entry<T> exact = null;
        Entry<T> bestPrefix = null;
        int bestPrefixLen = -1;
        Entry<T> defaultRule = null;

        for (Entry<T> e : entries) {
            if (e.matchesExact(worldLower)) {
                exact = e;
                break; // exact is the highest precedence; stop early
            }
            if (e.isDefaultRule()) {
                if (defaultRule == null) {
                    defaultRule = e;
                }
                continue;
            }
            int len = e.prefixMatchLength(worldLower);
            if (len > bestPrefixLen) {
                bestPrefixLen = len;
                bestPrefix = e;
            }
        }

        if (exact != null) {
            return exact.payload;
        }
        if (bestPrefix != null) {
            return bestPrefix.payload;
        }
        if (defaultRule != null) {
            return defaultRule.payload;
        }
        return null;
    }
}
