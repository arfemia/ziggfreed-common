package com.ziggfreed.common.world;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;

/**
 * "Which worlds does this apply to?", as a reusable nested-group codec ANY consumer asset can
 * embed as a field.
 *
 * <p><b>A world selector is a NAMED, REUSABLE MATCHER - a shorthand for match patterns - not an
 * opaque tag.</b> Everything here follows from that one sentence. {@code Names} references the
 * shared vocabulary that {@link WorldSelectorAsset} files contribute to; {@code Match} and
 * {@code GameplayConfig} are those same patterns written inline. Both routes produce a
 * {@link MatchRank}, so a rule referencing a name inherits the rank of whichever underlying
 * pattern actually matched and named and inline rules sort in ONE ordering - no priority knob,
 * nothing for a server owner to relearn. A tag would have no intrinsic specificity, and
 * precedence between two rules pointing at the same world would be undefined.
 *
 * <p>Authored shape (every key optional, every list nullable):
 * <pre>{@code
 * { "Names":          ["forgotten_temple"],
 *   "Match":          ["*Forgotten_Temple*"],
 *   "GameplayConfig": ["ForgottenTemple"],
 *   "ExcludeNames":   ["arena"] }
 * }</pre>
 *
 * <ul>
 *   <li><b>{@code Names}</b> - selector names contributed by {@code WorldSelectorAsset} files.
 *       Many-to-many: several assets may feed one name and the world's names are their union.</li>
 *   <li><b>{@code Match}</b> - inline world-name patterns, the exact grammar of
 *       {@link WorldNameMatcher} ({@code Foo} / {@code Foo*} / {@code *Foo} / {@code *Foo*} /
 *       {@code *}). Inline is ALWAYS available, so a one-off world never needs a selector asset.</li>
 *   <li><b>{@code GameplayConfig}</b> - exact matches against the world's authored
 *       {@code WorldConfig.GameplayConfig}. The uuid-free machine key of an instance world, and
 *       the top rank on the ladder.</li>
 *   <li><b>{@code ExcludeNames}</b> - a FILTER, not a complement: a world carrying any listed name
 *       is rejected even when the positive axes matched. A selector with ONLY {@code ExcludeNames}
 *       therefore matches <b>nothing</b> (there is no positive axis to filter). That reads as the
 *       opposite of what an author expects, so {@link WorldSelectorValidator} calls it out.</li>
 * </ul>
 *
 * <p><b>The codec carries NO defaults: an absent list stays null.</b> Read sites genuinely differ
 * (a placement may treat an empty selector as "the primary world", a rules table may treat an
 * unmatched world as its own DEFAULT record), so each read site applies its own default and this
 * type never invents one. One codec with two invisible Java-side defaults would be a rework.
 *
 * <p>Every leaf is registered with {@code appendInherited} so a native {@code Parent} partial
 * merge on the OWNING asset never drops an untouched sibling leaf.
 */
public final class WorldSelector {

    @Nullable protected String[] names;
    @Nullable protected String[] match;
    @Nullable protected String[] gameplayConfig;
    @Nullable protected String[] excludeNames;

    public static final BuilderCodec<WorldSelector> CODEC =
            BuilderCodec.builder(WorldSelector.class, WorldSelector::new)
                    .appendInherited(new KeyedCodec<>("Names", Codec.STRING_ARRAY, false),
                            (o, v) -> o.names = v, o -> o.names, (o, p) -> o.names = p.names).add()
                    .appendInherited(new KeyedCodec<>("Match", Codec.STRING_ARRAY, false),
                            (o, v) -> o.match = v, o -> o.match, (o, p) -> o.match = p.match).add()
                    .appendInherited(new KeyedCodec<>("GameplayConfig", Codec.STRING_ARRAY, false),
                            (o, v) -> o.gameplayConfig = v, o -> o.gameplayConfig,
                            (o, p) -> o.gameplayConfig = p.gameplayConfig).add()
                    .appendInherited(new KeyedCodec<>("ExcludeNames", Codec.STRING_ARRAY, false),
                            (o, v) -> o.excludeNames = v, o -> o.excludeNames,
                            (o, p) -> o.excludeNames = p.excludeNames).add()
                    .build();

    public WorldSelector() {
    }

    /** Java-side construction (tests, a consumer building a selector in code). Nulls stay null. */
    @Nonnull
    public static WorldSelector of(@Nullable String[] names, @Nullable String[] match,
            @Nullable String[] gameplayConfig, @Nullable String[] excludeNames) {
        WorldSelector s = new WorldSelector();
        s.names = names;
        s.match = match;
        s.gameplayConfig = gameplayConfig;
        s.excludeNames = excludeNames;
        return s;
    }

    @Nullable
    public String[] getNames() {
        return names;
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
    public String[] getExcludeNames() {
        return excludeNames;
    }

    /** True when no positive axis is authored, so this selector can never match a world. */
    public boolean hasNoPositiveAxis() {
        return isEmpty(names) && isEmpty(match) && isEmpty(gameplayConfig);
    }

    /** True when nothing at all is authored (the "absent stays null" case a read site defaults). */
    public boolean isBlank() {
        return hasNoPositiveAxis() && isEmpty(excludeNames);
    }

    // ==================== Matching ====================

    /**
     * Score this selector against a world, resolving {@code Names} through the cached
     * {@link WorldIdentity}. Returns the most specific {@link MatchRank} across every axis, or
     * {@code null} when the selector does not apply. Try-guarded: a failed world read degrades
     * to {@code null} (no match), never a throw.
     *
     * <p><b>World-thread</b> for the underlying world reads.
     */
    @Nullable
    public MatchRank match(@Nullable World world) {
        if (world == null) {
            return null;
        }
        try {
            return match(world.getName(), world.getWorldConfig().getGameplayConfig(),
                    WorldIdentity.indexFor(world));
        } catch (Throwable t) {
            warn("WorldSelector.match failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * The PURE matcher: score this selector against a world's name, its authored
     * {@code GameplayConfig}, and the world's already-resolved {@link WorldNameIndex}. Returns the
     * most specific rank across the three positive axes, or {@code null} when nothing matched or
     * an {@code ExcludeNames} entry vetoed the world. No engine coupling - this is the unit-tested
     * decision core {@link #match(World)} delegates to.
     */
    @Nullable
    public MatchRank match(@Nullable String worldName, @Nullable String worldGameplayConfig,
            @Nullable WorldNameIndex index) {
        WorldNameIndex idx = index != null ? index : WorldNameIndex.EMPTY;

        // ExcludeNames is a FILTER over the positive axes, never a complement of them: a
        // selector with nothing positive authored matches nothing, whatever it excludes.
        if (hasNoPositiveAxis()) {
            return null;
        }
        if (excludeNames != null) {
            for (String n : excludeNames) {
                if (n != null && !n.isBlank() && idx.has(n)) {
                    return null;
                }
            }
        }

        MatchRank best = directRank(match, gameplayConfig, worldName, worldGameplayConfig);
        if (names != null) {
            for (String n : names) {
                if (n == null || n.isBlank()) {
                    continue;
                }
                // A name reference inherits the rank of whichever pattern actually matched -
                // that is what keeps named and inline rules in one ordering.
                best = MatchRank.moreSpecific(best, idx.rankOf(n));
            }
        }
        return best;
    }

    /**
     * The ONE pattern-axis scorer, shared by an inline selector and by a
     * {@link WorldSelectorDef} contributing a name (so the two can never drift): the most
     * specific rank across an exact {@code GameplayConfig} hit and every matching name pattern,
     * or {@code null}. Ties keep the FIRST authored pattern.
     */
    @Nullable
    public static MatchRank directRank(@Nullable String[] matchPatterns, @Nullable String[] gameplayConfigs,
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
            ZiggfreedCommonPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
