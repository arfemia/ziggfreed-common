package com.ziggfreed.common.world;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;

/**
 * "Which worlds does this apply to?", as a reusable nested-group codec ANY consumer asset can
 * embed as a field under the key {@code Where}.
 *
 * <p><b>A world is named by what it is CALLED or by the gameplay config it runs.</b> There is no
 * intermediate vocabulary to learn: {@code Match} is a world-name pattern in the ordinary grammar,
 * {@code GameplayConfig} is the world's own authored config key, and both produce a
 * {@link MatchRank} so two rules pointing at one world are always ordered.
 *
 * <p>Authored shape (every key optional, every list nullable):
 * <pre>{@code
 * { "Match":          ["*Forgotten_Temple*"],
 *   "GameplayConfig": ["ForgottenTemple"],
 *   "ExcludeMatch":   ["*Arena*"] }
 * }</pre>
 *
 * <ul>
 *   <li><b>{@code Match}</b> - world-name patterns in the grammar of {@link WorldNameMatcher}:
 *       {@code Foo} (exact), {@code Foo*} (prefix), {@code *Foo} (suffix), {@code *Foo*}
 *       (contains), {@code *} (every world). A bare word is an EXACT name, so
 *       {@code ["default"]} applies to the world called {@code default} and to nothing else.</li>
 *   <li><b>{@code GameplayConfig}</b> - exact matches against the world's authored
 *       {@code WorldConfig.GameplayConfig} key. A live instance world is named
 *       {@code instance-<Name>-<random uuid>}, so its NAME changes every time it is entered while
 *       its config key does not; that is why this axis is the sturdiest way to reach an instance,
 *       and why it sits at the top of the ladder.</li>
 *   <li><b>{@code ExcludeMatch}</b> - name patterns in the SAME grammar as {@code Match}, applied
 *       as a FILTER over the positive axes rather than as a complement of them: a world whose name
 *       matches any of them is rejected even when a positive axis hit. A {@code Where} with ONLY
 *       {@code ExcludeMatch} therefore matches <b>nothing</b> (there is no positive axis left to
 *       filter), which reads as the opposite of what an author expects, so
 *       {@link WhereValidator} calls it out.</li>
 * </ul>
 *
 * <p><b>The codec carries NO defaults: an absent list stays null.</b> Read sites genuinely differ
 * (a placement may treat an empty {@code Where} as "the main world", a rules table may treat an
 * unmatched world as its own DEFAULT record), so each read site applies its own default and this
 * type never invents one. One codec with two invisible Java-side defaults would be a rework.
 *
 * <p>Every leaf is registered with {@code appendInherited} so a native {@code Parent} partial
 * merge on the OWNING asset never drops an untouched sibling leaf.
 */
public final class WorldSelector {

    @Nullable protected String[] match;
    @Nullable protected String[] gameplayConfig;
    @Nullable protected String[] excludeMatch;

    public static final BuilderCodec<WorldSelector> CODEC =
            BuilderCodec.builder(WorldSelector.class, WorldSelector::new)
                    .appendInherited(new KeyedCodec<>("Match", Codec.STRING_ARRAY, false),
                            (o, v) -> o.match = v, o -> o.match, (o, p) -> o.match = p.match)
                    .documentation("World-name patterns: Foo (exact), Foo* (prefix), *Foo (suffix), "
                            + "*Foo* (contains) or * (every world). A bare word is an exact name, so "
                            + "\"default\" means the world called default and nothing else. Only the "
                            + "*Foo* form reaches an instance world, whose name carries a random uuid.")
                    .add()
                    .appendInherited(new KeyedCodec<>("GameplayConfig", Codec.STRING_ARRAY, false),
                            (o, v) -> o.gameplayConfig = v, o -> o.gameplayConfig,
                            (o, p) -> o.gameplayConfig = p.gameplayConfig)
                    .documentation("Exact matches against a world's own authored GameplayConfig key. It "
                            + "carries no uuid and survives an instance being rebuilt, so it is the "
                            + "sturdiest way to target an instance world, and it outranks every name "
                            + "pattern.")
                    .add()
                    .appendInherited(new KeyedCodec<>("ExcludeMatch", Codec.STRING_ARRAY, false),
                            (o, v) -> o.excludeMatch = v, o -> o.excludeMatch,
                            (o, p) -> o.excludeMatch = p.excludeMatch)
                    .documentation("Name patterns, same grammar as Match, that REJECT a world even when "
                            + "a positive axis matched - for 'everywhere except' without enumerating "
                            + "the exceptions. It filters the axes above rather than standing in for "
                            + "them, so a Where carrying only ExcludeMatch matches nothing.")
                    .add()
                    .build();

    public WorldSelector() {
    }

    /** Java-side construction (tests, a consumer building a selector in code). Nulls stay null. */
    @Nonnull
    public static WorldSelector of(@Nullable String[] match, @Nullable String[] gameplayConfig,
            @Nullable String[] excludeMatch) {
        WorldSelector s = new WorldSelector();
        s.match = match;
        s.gameplayConfig = gameplayConfig;
        s.excludeMatch = excludeMatch;
        return s;
    }

    @Nullable
    public String[] getMatch() {
        return match;
    }

    @Nullable
    public String[] getGameplayConfig() {
        return gameplayConfig;
    }

    @Nullable
    public String[] getExcludeMatch() {
        return excludeMatch;
    }

    /** True when no positive axis is authored, so this selector can never match a world. */
    public boolean hasNoPositiveAxis() {
        return isEmpty(match) && isEmpty(gameplayConfig);
    }

    /** True when nothing at all is authored (the "absent stays null" case a read site defaults). */
    public boolean isBlank() {
        return hasNoPositiveAxis() && isEmpty(excludeMatch);
    }

    // ==================== Matching ====================

    /**
     * Score this selector against a world. Returns the most specific {@link MatchRank} across every
     * axis, or {@code null} when the selector does not apply. Try-guarded: a failed world read
     * degrades to {@code null} (no match), never a throw.
     *
     * <p><b>World-thread</b> for the underlying world reads.
     */
    @Nullable
    public MatchRank match(@Nullable World world) {
        if (world == null) {
            return null;
        }
        try {
            return match(world.getName(), world.getWorldConfig().getGameplayConfig());
        } catch (Throwable t) {
            warn("WorldSelector.match failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * The PURE matcher: score this selector against a world's name and its authored
     * {@code GameplayConfig}. Returns the most specific rank across the two positive axes, or
     * {@code null} when nothing matched or an {@code ExcludeMatch} pattern vetoed the world. No
     * engine coupling - this is the unit-tested decision core {@link #match(World)} delegates to.
     */
    @Nullable
    public MatchRank match(@Nullable String worldName, @Nullable String worldGameplayConfig) {
        // ExcludeMatch is a FILTER over the positive axes, never a complement of them: a selector
        // with nothing positive authored matches nothing, whatever it excludes.
        if (hasNoPositiveAxis()) {
            return null;
        }
        if (matchesAny(excludeMatch, worldName)) {
            return null;
        }
        return directRank(match, gameplayConfig, worldName, worldGameplayConfig);
    }

    /**
     * The pattern-axis scorer: the most specific rank across an exact {@code GameplayConfig} hit
     * and every matching name pattern, or {@code null}. Ties keep the FIRST authored pattern.
     */
    @Nullable
    private static MatchRank directRank(@Nullable String[] matchPatterns, @Nullable String[] gameplayConfigs,
            @Nullable String worldName, @Nullable String worldGameplayConfig) {
        MatchRank best = null;

        if (gameplayConfigs != null && worldGameplayConfig != null && !worldGameplayConfig.isBlank()) {
            String wanted = worldGameplayConfig.trim().toLowerCase(Locale.ROOT);
            for (String gc : gameplayConfigs) {
                if (gc != null && gc.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                    best = MatchRank.moreSpecific(best, MatchRank.gameplayConfig());
                    break; // band 0 is the top of the ladder; nothing can beat it.
                }
            }
        }

        if (matchPatterns != null && worldName != null && !worldName.isEmpty()) {
            String worldLower = worldName.toLowerCase(Locale.ROOT);
            for (String raw : matchPatterns) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Pattern p = Pattern.parse(raw);
                if (p.matches(worldLower)) {
                    best = MatchRank.moreSpecific(best, MatchRank.ofNamePattern(p));
                }
            }
        }
        return best;
    }

    /** Does {@code worldName} satisfy any of {@code patterns}? Blank entries are ignored. */
    private static boolean matchesAny(@Nullable String[] patterns, @Nullable String worldName) {
        if (patterns == null || worldName == null || worldName.isEmpty()) {
            return false;
        }
        String worldLower = worldName.toLowerCase(Locale.ROOT);
        for (String raw : patterns) {
            if (raw != null && !raw.isBlank() && Pattern.parse(raw).matches(worldLower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmpty(@Nullable String[] arr) {
        if (arr == null || arr.length == 0) {
            return true;
        }
        for (String s : arr) {
            if (s != null && !s.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
