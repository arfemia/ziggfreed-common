package com.ziggfreed.common.world;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the best-matching per-world payload for a world name - the generic world-name
 * selector shared by every consumer with "per-world config" semantics (the MMO's world
 * rules, the mob-scaling mod's per-world settings, a future minigame's world policies).
 * Instance worlds spawn with BOTH a leading {@code instance-} prefix AND a random suffix
 * ({@code instance-Dungeon_01_asdf334rf}), so matching supports a {@code *} at the start,
 * the end, or both.
 *
 * <p>Wildcard forms ({@code *} is the only metacharacter, and only at the ends):
 * <ul>
 *   <li>{@code "Foo"} - exact (case-insensitive)</li>
 *   <li>{@code "Foo_*"} - prefix: world starts with {@code Foo_}</li>
 *   <li>{@code "*_Foo"} - suffix: world ends with {@code _Foo}</li>
 *   <li>{@code "*Foo*"} - contains: world contains {@code Foo} anywhere (this is what
 *       catches an {@code instance-}-prefixed AND suffixed instance world, e.g.
 *       {@code instance-KweebecNightmare_Chase-<uuid>}; a bare trailing-{@code *} prefix
 *       does NOT, because the name starts with {@code instance-})</li>
 *   <li>{@code "*"} - the catch-all default rule</li>
 * </ul>
 *
 * <p>Precedence (most specific wins):
 * <ol>
 *   <li><b>exact</b> - the pattern equals the world name (case-insensitive)</li>
 *   <li><b>longest literal core</b> - across prefix/suffix/contains, the rule whose
 *       non-{@code *} core is longest wins (so {@code dungeon_ii*} beats {@code dungeon_i*}
 *       for {@code dungeon_ii}, and the bare {@code dungeon_i} world only matches
 *       {@code dungeon_i*} since the {@code _ii}/{@code _iii} cores are longer than it);
 *       ties break toward the more anchored form (prefix &gt; suffix &gt; contains)</li>
 *   <li><b>bare {@code *}</b> - the catch-all</li>
 *   <li>{@code null} - nothing matched (the caller falls back to its global settings)</li>
 * </ol>
 *
 * <p>Each {@link Entry} pre-parses its pattern once at config-load time so per-lookup cost
 * is a string compare / {@code startsWith} / {@code endsWith} / {@code contains}; a consumer
 * caches the resolution per world. Pure logic, zero engine coupling. Feature-matched to the
 * MMO's {@code WorldRulesMatcher} (this is the shared dedupe of that shape).
 */
public final class WorldNameMatcher {

    private WorldNameMatcher() {
    }

    /** Match kind, in descending anchoring rank (used only as a tie-break on equal core length). */
    private enum Kind {
        EXACT,     // no wildcard
        PREFIX,    // trailing "*"
        SUFFIX,    // leading "*"
        CONTAINS,  // leading AND trailing "*"
        ALL        // bare "*" (or "**"): the catch-all default
    }

    /** One authored rule: a pre-parsed match pattern bound to its payload. */
    public static final class Entry<T> {
        /** Raw pattern as authored, for diagnostics / validation (e.g. {@code "dungeon_i*"}). */
        @Nonnull
        public final String pattern;
        private final Kind kind;
        private final String core; // lower-cased pattern minus any leading/trailing "*"; "" for ALL
        @Nonnull
        public final T payload;

        public Entry(@Nonnull String pattern, @Nonnull T payload) {
            this.pattern = pattern;
            this.payload = payload;
            String p = pattern.trim().toLowerCase(Locale.ROOT);
            boolean lead = p.startsWith("*");
            boolean trail = p.endsWith("*");
            // Strip a single leading and/or trailing "*" to get the literal core.
            String c = p;
            if (trail) {
                c = c.substring(0, c.length() - 1);
            }
            if (lead && !c.isEmpty()) {
                c = c.substring(1);
            }
            this.core = c;
            if (c.isEmpty()) {
                this.kind = Kind.ALL;        // "*" or "**"
            } else if (lead && trail) {
                this.kind = Kind.CONTAINS;
            } else if (lead) {
                this.kind = Kind.SUFFIX;
            } else if (trail) {
                this.kind = Kind.PREFIX;
            } else {
                this.kind = Kind.EXACT;
            }
        }

        boolean isDefaultRule() {
            return kind == Kind.ALL;
        }

        boolean matchesExact(@Nonnull String worldLower) {
            return kind == Kind.EXACT && core.equals(worldLower);
        }

        /** Does this non-exact, non-ALL rule match {@code worldLower}? */
        private boolean matchesPartial(@Nonnull String worldLower) {
            return switch (kind) {
                case PREFIX -> worldLower.startsWith(core);
                case SUFFIX -> worldLower.endsWith(core);
                case CONTAINS -> worldLower.contains(core);
                default -> false;
            };
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
        Entry<T> bestPartial = null;
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
            if (e.matchesPartial(worldLower) && morePartialSpecific(e, bestPartial)) {
                bestPartial = e;
            }
        }

        if (exact != null) {
            return exact.payload;
        }
        if (bestPartial != null) {
            return bestPartial.payload;
        }
        if (defaultRule != null) {
            return defaultRule.payload;
        }
        return null;
    }

    /**
     * Is {@code candidate} a more specific partial match than the current best? Longer
     * literal core wins; on a tie the more anchored kind wins (prefix &gt; suffix &gt; contains).
     */
    private static boolean morePartialSpecific(@Nonnull Entry<?> candidate, @Nullable Entry<?> best) {
        if (best == null) {
            return true;
        }
        int byLen = Integer.compare(candidate.core.length(), best.core.length());
        if (byLen != 0) {
            return byLen > 0;
        }
        // Lower Kind ordinal = more anchored (PREFIX < SUFFIX < CONTAINS).
        return candidate.kind.ordinal() < best.kind.ordinal();
    }
}
