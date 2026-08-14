package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.validation.Finding;

/**
 * Audits an authored {@code Where} group for the mistakes that fail SILENTLY at runtime - a
 * selector that can never match anything, or one that describes no world the server actually has.
 * Neither produces an error anywhere; the content bound to it simply never appears.
 *
 * <p>Two entry points, because there are two questions.
 * {@link #validateSelector} asks only what the group says about ITSELF, so its answer is in hand
 * the moment the owning file decodes. {@link #validateAgainstWorlds} asks whether any world on
 * this server satisfies it, which is only meaningful once the universe is up - so a consumer runs
 * it from a late audit rather than from a layer fold.
 *
 * <p>Every check that consults the world beyond the group itself can answer "cannot tell", and an
 * empty answer always means exactly that - never "nothing exists". An audit run before boot
 * finishes, or in a unit JVM with no server at all, reports nothing rather than inventing a finding
 * against every file on the server.
 *
 * <p>Findings are shared {@link Finding} values, so a consumer maps them into its own reporting
 * framework alongside every other validator's.
 */
public final class WhereValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "where";

    /**
     * One world as this audit needs to see it: what it is called, and its authored
     * {@code GameplayConfig} key. The two axes a {@code Where} scores against, and nothing else, so
     * the checks below stay pure and testable with no server anywhere near them.
     */
    public record LoadedWorld(@Nullable String name, @Nullable String gameplayConfig) {
    }

    private WhereValidator() {
    }

    // ==================== the shape check ====================

    /**
     * Audit a {@link WorldSelector} group on its own terms. {@code contextId} labels the finding
     * (typically the owning asset's id plus its field name).
     */
    @Nonnull
    public static List<Finding> validateSelector(@Nullable WorldSelector selector,
            @Nonnull String contextId) {
        List<Finding> out = new ArrayList<>();
        if (selector == null) {
            return out;
        }

        boolean noPositive = selector.hasNoPositiveAxis();
        boolean hasExcludes = selector.getExcludeMatch() != null
                && !isAllBlank(selector.getExcludeMatch());

        if (noPositive && hasExcludes) {
            out.add(Finding.error(DOMAIN, "EXCLUDE_ONLY",
                    "ExcludeMatch is a filter over the positive axes, not a complement of them, so a "
                            + "Where with only ExcludeMatch matches NOTHING - add Match or "
                            + "GameplayConfig to say which worlds it applies to first", contextId));
        }

        reportBlankPatterns(selector.getMatch(), "Match", contextId, out);
        reportBlankPatterns(selector.getGameplayConfig(), "GameplayConfig", contextId, out);
        reportBlankPatterns(selector.getExcludeMatch(), "ExcludeMatch", contextId, out);
        return out;
    }

    // ==================== the describes-a-real-world check ====================

    /**
     * Report a {@code Where} that describes NONE of the worlds this server has loaded. That is the
     * misconfiguration a renamed main world produces: the file is well formed, every pattern is
     * spelled right, and the content simply never appears.
     *
     * <p>An empty {@code worlds} means "cannot tell" and reports nothing. Even with worlds in hand
     * this stays a WARNING rather than an error, because a {@code Where} aimed at an instance world
     * is CORRECT while no instance happens to be running.
     */
    @Nonnull
    public static List<Finding> validateAgainstWorlds(@Nullable WorldSelector selector,
            @Nonnull String contextId, @Nonnull Collection<LoadedWorld> worlds) {
        List<Finding> out = new ArrayList<>();
        if (selector == null || selector.hasNoPositiveAxis() || worlds.isEmpty()) {
            return out;
        }
        for (LoadedWorld world : worlds) {
            if (selector.match(world.name(), world.gameplayConfig()) != null) {
                return out;
            }
        }
        out.add(Finding.warning(DOMAIN, "MATCHES_NO_LOADED_WORLD",
                "this Where matches none of the worlds currently loaded (" + worldNames(worlds)
                        + "), so whatever it gates never appears - check its patterns against your "
                        + "server's real world names, or ignore this if it is aimed at an instance "
                        + "world that is not running", contextId));
        return out;
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
