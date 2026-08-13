package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.validation.Finding;

/**
 * Audits authored world selectors for the mistakes that fail SILENTLY at runtime - a selector
 * that names nothing, that names something it can never match, that references a name nobody
 * hands out, or that describes no world the server actually has - none of which produces an error
 * anywhere; the content bound to it simply never appears.
 *
 * <p>Three entry points, because there are three questions:
 * {@link #validate(WorldSelectorDef)} audits a {@link WorldSelectorAsset} file on its own terms,
 * {@link #validateSelector} audits a {@link WorldSelector} group embedded in some other asset, and
 * {@link #validateNames} is the ONE place that answers "is this a name anybody hands out?" - so a
 * placement, a dialogue and any future selector-aware surface all ask it the same way and report
 * the same finding instead of each keeping a private scan of the selector pool. Findings are
 * shared {@link Finding} values, so a consumer maps them into its own reporting framework
 * alongside every other validator's.
 *
 * <p>Every check that consults the world beyond the file itself can answer "cannot tell", and an
 * empty answer always means exactly that - never "nothing exists". An audit run before the assets
 * fold, or in a unit JVM with no server at all, reports nothing rather than inventing a finding
 * against every selector on the server.
 */
public final class WorldSelectorValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "worldselector";

    /**
     * One world as this audit needs to see it: what it is called, and its authored
     * {@code GameplayConfig} key. The two axes a selector scores against, and nothing else, so the
     * checks below stay pure and testable with no server anywhere near them.
     */
    public record LoadedWorld(@Nullable String name, @Nullable String gameplayConfig) {
    }

    private WorldSelectorValidator() {
    }

    @Nonnull
    public static List<Finding> validateAll(@Nonnull Collection<WorldSelectorDef> defs) {
        List<Finding> out = new ArrayList<>();
        for (WorldSelectorDef def : defs) {
            if (def != null) {
                validate(def, out);
            }
        }
        return out;
    }

    @Nonnull
    public static List<Finding> validate(@Nonnull WorldSelectorDef def) {
        List<Finding> out = new ArrayList<>();
        validate(def, out);
        return out;
    }

    private static void validate(@Nonnull WorldSelectorDef def, @Nonnull List<Finding> out) {
        String id = def.id();

        String[] raw = def.rawNames();
        if (raw != null) {
            for (String n : raw) {
                if (n == null || n.isBlank()) {
                    out.add(Finding.error(DOMAIN, "BLANK_NAME",
                            "Names contains a blank entry - a blank name can never be referenced", id));
                    break;
                }
            }
        }

        if (def.hasNoNames()) {
            out.add(Finding.error(DOMAIN, "MISSING_NAMES",
                    "Names is required: the name IS this file's contribution, and the asset id is a "
                            + "pure address that is never used as one", id));
            return;
        }

        if (def.matchesNothing()) {
            out.add(Finding.warning(DOMAIN, "MATCHES_NOTHING",
                    "Names " + def.names() + " is contributed but no Match pattern or GameplayConfig "
                            + "value is authored, so no world can ever earn it", id));
        }

        reportBlankPatterns(def.match(), "Match", id, out);
        reportBlankPatterns(def.gameplayConfig(), "GameplayConfig", id, out);
        reportBlankPatterns(def.excludeNames(), "ExcludeNames", id, out);
    }

    /**
     * Audit a {@link WorldSelector} group embedded in a consumer asset. {@code contextId} labels
     * the finding (typically the owning asset's id plus its field name).
     */
    @Nonnull
    public static List<Finding> validateSelector(@Nullable WorldSelector selector, @Nonnull String contextId) {
        List<Finding> out = new ArrayList<>();
        if (selector == null) {
            return out;
        }

        boolean noPositive = selector.hasNoPositiveAxis();
        boolean hasExcludes = selector.getExcludeNames() != null
                && !isAllBlank(selector.getExcludeNames());

        if (noPositive && hasExcludes) {
            out.add(Finding.error(DOMAIN, "EXCLUDE_ONLY",
                    "ExcludeNames is a filter over the positive axes, not a complement of them, so a "
                            + "selector with only ExcludeNames matches NOTHING - add Names, Match, or "
                            + "GameplayConfig to say which worlds it applies to first", contextId));
        }

        reportBlankPatterns(selector.getMatch(), "Match", contextId, out);
        reportBlankPatterns(selector.getGameplayConfig(), "GameplayConfig", contextId, out);
        reportBlankPatterns(selector.getNames(), "Names", contextId, out);
        reportBlankPatterns(selector.getExcludeNames(), "ExcludeNames", contextId, out);
        return out;
    }

    // ==================== the shared name-is-known check ====================

    /**
     * Every selector name any loaded {@link WorldSelectorAsset} hands out, lower-cased. EMPTY
     * means the pool has not folded yet or could not be read - callers must treat that as "cannot
     * tell", which is what {@link #validateNames} does for them.
     */
    @Nonnull
    public static Set<String> knownNames() {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (WorldSelectorDef def : WorldSelectorConfig.getInstance().all().values()) {
                if (def != null) {
                    out.addAll(def.names());
                }
            }
        } catch (Throwable ignored) {
            return Set.of();
        }
        return out;
    }

    /** Does any loaded selector asset hand out {@code name}? Reads the live pool. */
    public static boolean isKnownName(@Nullable String name) {
        return contains(knownNames(), name);
    }

    /**
     * The ONE unknown-selector-name check. A name nothing hands out can never match a world, so
     * whatever it gates is invisible forever with no error anywhere - and that is true of a
     * placement, a dialogue condition, or anything else that grew a {@code Names} list.
     *
     * <p>Reported as a WARNING, never an error: the mod that hands out the name may simply not be
     * installed on this server, and a name it would contribute is a perfectly reasonable thing for
     * content to reference.
     *
     * @param names      the authored names ({@code null} or empty reports nothing)
     * @param field      the authoring key the names came from, for the message ({@code "Names"})
     * @param contextId  what to file the finding against (the asset id, plus a path if useful)
     * @param knownNames the vocabulary to check against, usually {@link #knownNames()};
     *                   {@code null} or EMPTY means "cannot tell" and reports nothing
     */
    @Nonnull
    public static List<Finding> validateNames(@Nullable String[] names, @Nonnull String field,
            @Nonnull String contextId, @Nullable Set<String> knownNames) {
        List<Finding> out = new ArrayList<>();
        if (names == null || knownNames == null || knownNames.isEmpty()) {
            return out;
        }
        for (String name : names) {
            if (name == null || name.isBlank() || contains(knownNames, name)) {
                continue;
            }
            out.add(Finding.warning(DOMAIN, "UNKNOWN_SELECTOR_NAME",
                    field + " names the world selector '" + name + "', which no loaded selector asset "
                            + "hands out, so nothing bound to it can ever match a world", contextId));
        }
        return out;
    }

    // ==================== the describes-a-real-world check ====================

    /**
     * Report every selector in {@code defs} that describes NONE of the worlds this server has
     * loaded. That is the misconfiguration a renamed main world produces: the file is well formed,
     * its name is referenced correctly everywhere, and the content simply never appears.
     *
     * <p>An empty {@code worlds} means "cannot tell" and reports nothing. Even with worlds in
     * hand this stays a WARNING rather than an error, because a selector aimed at an instance
     * world is CORRECT while no instance happens to be running.
     */
    @Nonnull
    public static List<Finding> validateAgainstWorlds(@Nonnull Collection<WorldSelectorDef> defs,
            @Nonnull Collection<LoadedWorld> worlds) {
        List<Finding> out = new ArrayList<>();
        if (worlds.isEmpty()) {
            return out;
        }
        for (WorldSelectorDef def : defs) {
            if (def == null || def.hasNoNames() || def.matchesNothing() || matchesAny(def, worlds)) {
                continue;
            }
            out.add(Finding.warning(DOMAIN, "MATCHES_NO_LOADED_WORLD",
                    "Names " + def.names() + " matches none of the worlds currently loaded ("
                            + worldNames(worlds) + ") - check the patterns against your server's real "
                            + "world names, or ignore this if the selector is aimed at an instance "
                            + "world that is not running", def.id()));
        }
        return out;
    }

    /**
     * The same question for a {@link WorldSelector} embedded in a consumer asset: does it describe
     * any loaded world, resolving its {@code Names} through {@code pool} the way the runtime does?
     * An empty {@code worlds} reports nothing.
     */
    @Nonnull
    public static List<Finding> validateSelectorAgainstWorlds(@Nullable WorldSelector selector,
            @Nonnull String contextId, @Nonnull Collection<LoadedWorld> worlds,
            @Nonnull Collection<WorldSelectorDef> pool) {
        List<Finding> out = new ArrayList<>();
        if (selector == null || selector.hasNoPositiveAxis() || worlds.isEmpty()) {
            return out;
        }
        for (LoadedWorld world : worlds) {
            WorldNameIndex index = WorldIdentity.resolve(world.name(), world.gameplayConfig(), pool);
            if (selector.match(world.name(), world.gameplayConfig(), index) != null) {
                return out;
            }
        }
        out.add(Finding.warning(DOMAIN, "MATCHES_NO_LOADED_WORLD",
                "this selector matches none of the worlds currently loaded (" + worldNames(worlds)
                        + "), so whatever it gates never appears - check it against your server's real "
                        + "world names, or ignore this if it is aimed at an instance world that is not "
                        + "running", contextId));
        return out;
    }

    private static boolean matchesAny(@Nonnull WorldSelectorDef def, @Nonnull Collection<LoadedWorld> worlds) {
        for (LoadedWorld world : worlds) {
            if (def.rankFor(world.name(), world.gameplayConfig()) != null) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String worldNames(@Nonnull Collection<LoadedWorld> worlds) {
        StringBuilder sb = new StringBuilder();
        for (LoadedWorld world : worlds) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(world.name() == null ? "?" : world.name());
        }
        return sb.toString();
    }

    private static boolean contains(@Nonnull Set<String> names, @Nullable String name) {
        return name != null && !name.isBlank() && names.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    private static void reportBlankPatterns(@Nullable String[] values, @Nonnull String field,
            @Nonnull String sourceId, @Nonnull List<Finding> out) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (v == null || v.isBlank()) {
                out.add(Finding.warning(DOMAIN, "BLANK_ENTRY",
                        field + " contains a blank entry, which is ignored", sourceId));
                return;
            }
        }
    }

    private static boolean isAllBlank(@Nonnull String[] values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
