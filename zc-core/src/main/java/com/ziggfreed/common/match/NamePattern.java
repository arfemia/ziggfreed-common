package com.ziggfreed.common.match;

import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * THE name-pattern grammar: how a string like {@code *Forgotten_Temple*} is parsed, matched and
 * scored. A world name, a block id, a mob id - anywhere in the library an author points at a NAME
 * rather than at one exact thing, this is the spelling, so an author learns one grammar and a mod
 * never invents a second one.
 *
 * <p><b>{@code *} is the only metacharacter, and only at the ends.</b> That is deliberate: the
 * names being matched (an instance world spawns as
 * {@code instance-KweebecNightmare_Chase-<uuid>}, an ore block reads {@code Rock_Gem_Zephyr}) carry
 * their identifying token at the front, the back, or buried in the middle, and those three cases
 * are the whole of what an author needs. A regex would cover them too and would also let a single
 * authored line cost unbounded time on every lookup.
 *
 * <ul>
 *   <li>{@code "Foo"} - exact (case-insensitive)</li>
 *   <li>{@code "Foo*"} - prefix: the name starts with {@code Foo}</li>
 *   <li>{@code "*Foo"} - suffix: the name ends with {@code Foo}</li>
 *   <li>{@code "*Foo*"} - contains: {@code Foo} appears anywhere. This is the only form that
 *       catches a name carrying BOTH a leading engine prefix and a trailing random suffix, which is
 *       what an instance world looks like</li>
 *   <li>{@code "*"} - the catch-all rule, the one that applies when nothing more specific does</li>
 * </ul>
 *
 * <p><b>This class parses and scores; it does not SELECT.</b> Picking a winner among several
 * candidates is {@link NameMatchRank} plus whatever consumer owns the candidate list, so every
 * surface sorts in the same order and none keeps a private ladder of its own.
 *
 * <p>Parse once at load, match many times: a parsed pattern is a {@link Kind} plus a lower-cased
 * literal {@link #core()}, so a lookup costs one {@code equals} / {@code startsWith} /
 * {@code endsWith} / {@code contains}. Pure logic, zero engine coupling.
 *
 * <p><b>Subclassable on purpose, and for one narrow reason.</b> A consumer that published its own
 * pattern type before this grammar was shared can keep that name by extending this class, so its
 * callers keep compiling while the grammar itself exists once
 * ({@code world.WorldNameMatcher.Pattern} is the case that shaped this). Do NOT extend it to change
 * how a pattern parses or matches - a second grammar wearing this type is exactly what the class
 * exists to prevent.
 */
public class NamePattern {

    /** Match kind, in descending anchoring rank (the tie-break on equal core length). */
    public enum Kind {
        EXACT,     // no wildcard
        PREFIX,    // trailing "*"
        SUFFIX,    // leading "*"
        CONTAINS,  // leading AND trailing "*"
        ALL        // bare "*" (or "**"): the catch-all default
    }

    /** Raw pattern as authored, for diagnostics / validation (e.g. {@code "dungeon_i*"}). */
    @Nonnull
    public final String raw;

    private final Kind kind;

    /** Lower-cased pattern minus any leading/trailing {@code *}; empty for {@link Kind#ALL}. */
    @Nonnull
    private final String core;

    /**
     * Parse {@code pattern} into its kind + literal core. Protected so a subclass can chain to it;
     * everything else calls {@link #parse}.
     */
    protected NamePattern(@Nonnull String pattern) {
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
    public static NamePattern parse(@Nonnull String pattern) {
        return new NamePattern(pattern);
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

    /** Is this the bare {@code *} catch-all, the rule that applies when nothing more specific does? */
    public boolean isDefaultRule() {
        return kind == Kind.ALL;
    }

    /**
     * Exact-kind pattern equal to {@code candidateLower}, which the caller has ALREADY lower-cased.
     * Lower-casing is the caller's job because a candidate is normally tested against many patterns
     * in a row, and doing it per pattern would repeat the same allocation per rule.
     */
    public boolean matchesExact(@Nonnull String candidateLower) {
        return kind == Kind.EXACT && core.equals(candidateLower);
    }

    /** Does this non-exact, non-ALL pattern match {@code candidateLower} (already lower-cased)? */
    public boolean matchesPartial(@Nonnull String candidateLower) {
        return switch (kind) {
            case PREFIX -> candidateLower.startsWith(core);
            case SUFFIX -> candidateLower.endsWith(core);
            case CONTAINS -> candidateLower.contains(core);
            default -> false;
        };
    }

    /** Does this pattern match at all, in ANY kind (exact, partial, or the bare catch-all)? */
    public boolean matches(@Nonnull String candidateLower) {
        return kind == Kind.ALL || matchesExact(candidateLower) || matchesPartial(candidateLower);
    }
}
