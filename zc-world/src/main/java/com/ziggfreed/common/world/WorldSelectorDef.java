package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The resolved runtime model of one {@link WorldSelectorAsset}: the selector NAMES this asset
 * contributes, plus the patterns a world must satisfy to earn them.
 *
 * <p>The asset id is a pure ADDRESS (owner overrides and native {@code Parent} use it) and is
 * never semantic. The names are the shared vocabulary, and they are many-to-many: two mods may
 * each ship a file contributing patterns to one name, and a world earns the name from EITHER -
 * which is exactly what makes a colliding filename harmless.
 *
 * <p>Immutable; pure logic, zero engine coupling.
 */
public final class WorldSelectorDef {

    private final String id;
    private final String[] rawNames;
    private final List<String> names;
    private final String[] match;
    private final String[] gameplayConfig;
    private final String[] excludeNames;
    private final List<String> excludes;

    public WorldSelectorDef(@Nonnull String id, @Nullable String[] names,
            @Nullable String[] match, @Nullable String[] gameplayConfig,
            @Nullable String[] excludeNames) {
        this.id = id;
        this.rawNames = names == null ? null : names.clone();
        this.names = normalizeNames(names);
        this.match = match == null ? null : match.clone();
        this.gameplayConfig = gameplayConfig == null ? null : gameplayConfig.clone();
        this.excludeNames = excludeNames == null ? null : excludeNames.clone();
        this.excludes = normalizeNames(excludeNames);
    }

    /** The asset id (a pure address - the file this came from), lower-cased by the config fold. */
    @Nonnull
    public String id() {
        return id;
    }

    /** The names this asset contributes, lower-cased, blanks dropped, order preserved. */
    @Nonnull
    public List<String> names() {
        return names;
    }

    /**
     * The {@code Names} list exactly as authored, blanks and all - the validator needs the
     * unfiltered form to tell "authored an empty string" apart from "authored nothing".
     */
    @Nullable
    public String[] rawNames() {
        return rawNames == null ? null : rawNames.clone();
    }

    /** The inline world-name patterns, as authored ({@code null} when none). */
    @Nullable
    public String[] match() {
        return match == null ? null : match.clone();
    }

    /** The exact {@code GameplayConfig} values, as authored ({@code null} when none). */
    @Nullable
    public String[] gameplayConfig() {
        return gameplayConfig == null ? null : gameplayConfig.clone();
    }

    /** The {@code ExcludeNames} list exactly as authored, blanks and all ({@code null} when none). */
    @Nullable
    public String[] excludeNames() {
        return excludeNames == null ? null : excludeNames.clone();
    }

    /** The names this asset refuses to apply beside, lower-cased, blanks dropped. */
    @Nonnull
    public List<String> excludes() {
        return excludes;
    }

    /**
     * Does {@code worldNames} carry a name this asset excludes? The argument is the set a world
     * earns from every asset's POSITIVE axes, resolved before any exclusion is applied, which is
     * what keeps the answer independent of the order the assets folded in.
     */
    public boolean excludedBy(@Nonnull Set<String> worldNames) {
        for (String name : excludes) {
            if (worldNames.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Score this asset's patterns against a world, through the same
     * {@link WorldSelector#directRank} an inline selector uses (one scorer, so a named and an
     * inline rule can never drift). {@code null} = this asset does not describe that world.
     */
    @Nullable
    public MatchRank rankFor(@Nullable String worldName, @Nullable String worldGameplayConfig) {
        return WorldSelector.directRank(match, gameplayConfig, worldName, worldGameplayConfig);
    }

    /** True when this asset names nothing - a validation error (the name IS the contribution). */
    public boolean hasNoNames() {
        return names.isEmpty();
    }

    /** True when this asset names something but can never match a world (no pattern authored). */
    public boolean matchesNothing() {
        return isEmpty(match) && isEmpty(gameplayConfig);
    }

    @Nonnull
    private static List<String> normalizeNames(@Nullable String[] raw) {
        if (raw == null || raw.length == 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(raw.length);
        for (String n : raw) {
            if (n == null || n.isBlank()) {
                continue;
            }
            String key = n.trim().toLowerCase(Locale.ROOT);
            if (!out.contains(key)) {
                out.add(key);
            }
        }
        return Collections.unmodifiableList(out);
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
}
