package com.ziggfreed.common.world;

import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * THE world-name pattern grammar: how a string like {@code *Forgotten_Temple*} is parsed, matched
 * and scored. Every world-targeting field in the library reads through it, so an author learns one
 * grammar and a mod never invents a second one.
 *
 * <p>Instance worlds spawn with BOTH a leading {@code instance-} prefix AND a random suffix
 * ({@code instance-Dungeon_01_asdf334rf}), so matching supports a {@code *} at the start,
 * the end, or both.
 *
 * <p><b>This class parses and scores; it does not SELECT.</b> Picking a winner among several
 * candidates is {@link WorldSelector} plus {@link MatchRank} - one selection path, so named and
 * inline rules sort in the same order and no consumer keeps a private ladder of its own.
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
 * <p>Precedence, expressed as a {@link MatchRank} (most specific wins):
 * <ol>
 *   <li><b>exact</b> - the pattern equals the world name (case-insensitive)</li>
 *   <li><b>longest literal core</b> - across prefix/suffix/contains, the rule whose
 *       non-{@code *} core is longest wins (so {@code dungeon_ii*} beats {@code dungeon_i*}
 *       for {@code dungeon_ii}, and the bare {@code dungeon_i} world only matches
 *       {@code dungeon_i*} since the {@code _ii}/{@code _iii} cores are longer than it);
 *       ties break toward the more anchored form (prefix &gt; suffix &gt; contains)</li>
 *   <li><b>bare {@code *}</b> - the catch-all</li>
 *   <li>nothing matched - the caller falls back to its global settings</li>
 * </ol>
 *
 * <p>{@link Pattern} pre-parses a pattern once at config-load time (into a {@link Kind} plus a
 * lower-cased literal core) so per-lookup cost is a string compare / {@code startsWith} /
 * {@code endsWith} / {@code contains}; a consumer caches the resolution per world. Pure logic,
 * zero engine coupling, and the ONE parse implementation - {@link MatchRank} is built straight off
 * {@link Pattern#kind()} + {@link Pattern#coreLength()}, so a validator reasoning about a pattern
 * and the runtime matching it can never disagree.
 */
public final class WorldNameMatcher {

    private WorldNameMatcher() {
    }

    /** Match kind, in descending anchoring rank (used only as a tie-break on equal core length). */
    public enum Kind {
        EXACT,     // no wildcard
        PREFIX,    // trailing "*"
        SUFFIX,    // leading "*"
        CONTAINS,  // leading AND trailing "*"
        ALL        // bare "*" (or "**"): the catch-all default
    }

    /**
     * One pre-parsed match pattern: its {@link Kind} plus its lower-cased literal core
     * (the pattern minus any leading/trailing {@code *}). Parse once at load, then match
     * many times. Also the scoring input for {@link MatchRank}.
     */
    public static final class Pattern {

        /** Raw pattern as authored, for diagnostics / validation (e.g. {@code "dungeon_i*"}). */
        @Nonnull
        public final String raw;
        private final Kind kind;
        private final String core; // lower-cased pattern minus any leading/trailing "*"; "" for ALL

        public Pattern(@Nonnull String pattern) {
            this.raw = pattern;
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

        /** Parse {@code pattern} into its kind + literal core. */
        @Nonnull
        public static Pattern parse(@Nonnull String pattern) {
            return new Pattern(pattern);
        }

        @Nonnull
        public Kind kind() {
            return kind;
        }

        /** The lower-cased literal core (the pattern minus its wildcards); empty for {@link Kind#ALL}. */
        @Nonnull
        public String core() {
            return core;
        }

        /** Length of the literal core - the primary specificity measure for a partial match. */
        public int coreLength() {
            return core.length();
        }

        public boolean isDefaultRule() {
            return kind == Kind.ALL;
        }

        /** Exact-kind pattern equal to the (already lower-cased) world name. */
        public boolean matchesExact(@Nonnull String worldLower) {
            return kind == Kind.EXACT && core.equals(worldLower);
        }

        /** Does this non-exact, non-ALL pattern match {@code worldLower}? */
        public boolean matchesPartial(@Nonnull String worldLower) {
            return switch (kind) {
                case PREFIX -> worldLower.startsWith(core);
                case SUFFIX -> worldLower.endsWith(core);
                case CONTAINS -> worldLower.contains(core);
                default -> false;
            };
        }

        /** Does this pattern match at all, in ANY kind (exact, partial, or the bare catch-all)? */
        public boolean matches(@Nonnull String worldLower) {
            return kind == Kind.ALL || matchesExact(worldLower) || matchesPartial(worldLower);
        }
    }

}
