package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;

/**
 * "Which worlds does this apply to?", as a reusable nested-group codec ANY consumer asset can
 * embed as a field under the key {@code Where}.
 *
 * <p><b>A world is named by what it is CALLED, by the gameplay config it runs, or by the NPC
 * placement that may stand in it.</b> There is no intermediate vocabulary to learn: {@code Match} is a
 * world-name pattern in the ordinary grammar, {@code GameplayConfig} is the world's own authored config
 * key, {@code Placement} borrows an NPC placement's own {@code Where}, and each produces a
 * {@link MatchRank} so two rules pointing at one world are always ordered.
 *
 * <p>Authored shape (every key optional, every list nullable):
 * <pre>{@code
 * { "Match":          ["*Forgotten_Temple*"],
 *   "GameplayConfig": ["ForgottenTemple"],
 *   "Placement":      ["YourMod_Guide_Temple"],
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
 *   <li><b>{@code Placement}</b> - NPC placement ids, matched without regard to case. A world
 *       matches when any named placement's own {@code Where} matches it (the server owner's
 *       {@code npc-placements.json} included), scored at the rank that {@code Where} matched with,
 *       whether or not its NPC is standing there right now. So a storefront, a board or a line
 *       written against a placement follows its NPC when a server moves it. The placement store
 *       answers through the {@link PlacementWheres} seam, since this module cannot see it. There
 *       are no chains: a {@code Placement} axis inside the {@code Where} a placement answers with
 *       resolves to nothing ({@link #withoutPlacement}), which {@link WhereValidator} reports.</li>
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
 * <p><b>Under native {@code Parent} inheritance, an authored {@code Where} replaces the parent's
 * selector WHOLESALE.</b> A {@code Where} is ONE predicate: its positive axes are alternatives
 * scored on one ladder, so a child inheriting the parent's {@code Match} underneath its own
 * {@code GameplayConfig} would silently broaden itself to worlds nobody authored it for (that was
 * a shipped bug: a temple-only child placement inherited {@code Match:["default"]} and stood a
 * duplicate NPC at the main world's spawn). The engine merges a nested group PER LEAF, and a leaf
 * participates only when it registers an inherit function - so these leaves deliberately register
 * NONE. A child authoring no {@code Where} at all still inherits the parent's whole selector
 * wherever the OWNING asset registers its {@code Where} field with an inherit function (the NPC
 * placement asset does); a child that authors one and still wants the parent's
 * {@code ExcludeMatch} restates it, where a reader can see it.
 */
public final class WorldSelector {

    @Nullable protected String[] match;
    @Nullable protected String[] gameplayConfig;
    @Nullable protected String[] placement;
    @Nullable protected String[] excludeMatch;

    public static final BuilderCodec<WorldSelector> CODEC =
            BuilderCodec.builder(WorldSelector.class, WorldSelector::new)
                    .append(new KeyedCodec<>("Match", Codec.STRING_ARRAY, false),
                            (o, v) -> o.match = v, o -> o.match)
                    .documentation("World-name patterns: Foo (exact), Foo* (prefix), *Foo (suffix), "
                            + "*Foo* (contains) or * (every world). A bare word is an exact name, so "
                            + "\"default\" means the world called default and nothing else. Only the "
                            + "*Foo* form reaches an instance world, whose name carries a random uuid.")
                    .add()
                    .append(new KeyedCodec<>("GameplayConfig", Codec.STRING_ARRAY, false),
                            (o, v) -> o.gameplayConfig = v, o -> o.gameplayConfig)
                    .documentation("Exact matches against a world's own authored GameplayConfig key. It "
                            + "carries no uuid and survives an instance being rebuilt, so it is the "
                            + "sturdiest way to target an instance world, and it outranks every name "
                            + "pattern.")
                    .add()
                    .append(new KeyedCodec<>("Placement", Codec.STRING_ARRAY, false),
                            (o, v) -> o.placement = v, o -> o.placement)
                    .documentation("NPC placement ids, in any casing. Matches every world one of those "
                            + "placements may stand in, as that placement's own Where says (the server "
                            + "owner's npc-placements.json included), whether or not its NPC is standing "
                            + "there right now, so this follows the NPC when a server moves it. It ranks "
                            + "as that Where matched. A placement's own Where cannot use it.")
                    .add()
                    .append(new KeyedCodec<>("ExcludeMatch", Codec.STRING_ARRAY, false),
                            (o, v) -> o.excludeMatch = v, o -> o.excludeMatch)
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
        return of(match, gameplayConfig, excludeMatch, null);
    }

    /** {@link #of(String[], String[], String[])} with a {@code Placement} axis too. Nulls stay null. */
    @Nonnull
    public static WorldSelector of(@Nullable String[] match, @Nullable String[] gameplayConfig,
            @Nullable String[] excludeMatch, @Nullable String[] placement) {
        WorldSelector s = new WorldSelector();
        s.match = match;
        s.gameplayConfig = gameplayConfig;
        s.excludeMatch = excludeMatch;
        s.placement = placement;
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

    /** The NPC placement ids the {@code Placement} axis names, as authored, or null. */
    @Nullable
    public String[] getPlacement() {
        return placement;
    }

    @Nullable
    public String[] getExcludeMatch() {
        return excludeMatch;
    }

    /** True when no positive axis is authored, so this selector can never match a world. */
    public boolean hasNoPositiveAxis() {
        return isEmpty(match) && isEmpty(gameplayConfig) && isEmpty(placement);
    }

    /** True when nothing at all is authored (the "absent stays null" case a read site defaults). */
    public boolean isBlank() {
        return hasNoPositiveAxis() && isEmpty(excludeMatch);
    }

    /**
     * This selector with its {@code Placement} axis dropped, the rest unchanged: what a placement's own
     * {@code Where} means, since a placement cannot follow another placement (no chains). Answers this
     * very selector when it names no placement.
     */
    @Nonnull
    public WorldSelector withoutPlacement() {
        if (isEmpty(placement)) {
            return this;
        }
        return of(match, gameplayConfig, excludeMatch, null);
    }

    /**
     * The {@code Where} of each placement the {@code Placement} axis names, in authored order, each with its
     * own {@code Placement} axis dropped ({@link #withoutPlacement}); an id no placement answers to adds
     * nothing. Empty when the axis is unauthored, without asking the {@link PlacementWheres} seam at all.
     */
    @Nonnull
    public List<WorldSelector> placementWheres() {
        if (isEmpty(placement)) {
            return List.of();
        }
        List<WorldSelector> out = new ArrayList<>(placement.length);
        for (String id : placement) {
            if (id == null || id.isBlank()) {
                continue;
            }
            WorldSelector where = PlacementWheres.whereOf(id);
            if (where != null) {
                out.add(where.withoutPlacement());
            }
        }
        return out;
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
     * The matcher: score this selector against a world's name and its authored {@code GameplayConfig}.
     * Returns the most specific rank across the positive axes, or {@code null} when nothing matched or
     * an {@code ExcludeMatch} pattern vetoed the world. No engine coupling - this is the unit-tested
     * decision core {@link #match(World)} delegates to. A {@code Placement} axis scores each named
     * placement's own {@code Where} against the same world ({@link #placementWheres}); this selector's
     * own axes come first, so a tie keeps them, then the placements in authored order.
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
        MatchRank best = directRank(match, gameplayConfig, worldName, worldGameplayConfig);
        for (WorldSelector where : placementWheres()) {
            // Each answer carries no Placement axis of its own, so this never recurses.
            best = MatchRank.moreSpecific(best, where.match(worldName, worldGameplayConfig));
        }
        return best;
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
        SafeLog.warn(message);
    }
}
