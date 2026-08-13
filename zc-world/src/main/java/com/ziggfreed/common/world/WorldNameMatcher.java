package com.ziggfreed.common.world;

import javax.annotation.Nonnull;

import com.ziggfreed.common.match.NamePattern;

/**
 * The WORLD flavour of the shared name-pattern grammar. The grammar itself - what {@code *} means,
 * where it may sit, how a pattern is scored - lives once in
 * {@link com.ziggfreed.common.match.NamePattern}; this class is the world-side handle on it, so a
 * world-targeting field and a loot trigger key can never disagree about what a pattern means.
 *
 * <p>What the grammar buys HERE: instance worlds spawn with BOTH a leading {@code instance-} prefix
 * AND a random suffix ({@code instance-Dungeon_01-9f3a}), so the contains form {@code *Dungeon_01*}
 * is the only one that reaches them - a bare trailing-{@code *} prefix cannot, because the name
 * starts with {@code instance-}. The main world is named {@code default}, which an exact pattern
 * matches.
 *
 * <p>Precedence is {@link MatchRank}: an exact {@code GameplayConfig} hit, then an exact name, then
 * the longest literal core across prefix / suffix / contains (anchoring only breaking a tie), then
 * the bare {@code *}, then nothing matched and the caller falls back to its global settings.
 *
 * <p><b>This class parses and scores; it does not SELECT.</b> Picking a winner among several
 * candidates is {@link WorldSelector} plus {@link MatchRank} - one selection path, so named and
 * inline rules sort in the same order and no consumer keeps a private ladder of its own.
 */
public final class WorldNameMatcher {

    private WorldNameMatcher() {
    }

    /**
     * One pre-parsed world-name pattern. It IS a {@link NamePattern} - same parse, same matching,
     * same core - and exists as its own type only so that {@code Pattern.parse(...)} keeps
     * answering with this world-side name for the callers that already speak it.
     *
     * <p>The shared {@link NamePattern.Kind} vocabulary is what {@link #kind()} answers with, so a
     * world rule and a loot trigger describe the same pattern shape in the same words.
     */
    public static final class Pattern extends NamePattern {

        public Pattern(@Nonnull String pattern) {
            super(pattern);
        }

        /** Parse {@code pattern} into its kind + literal core. */
        @Nonnull
        public static Pattern parse(@Nonnull String pattern) {
            return new Pattern(pattern);
        }
    }
}
