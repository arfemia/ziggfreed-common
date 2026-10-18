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
 * {@link #validateSelector} asks what the group says about ITSELF, so its answer is in hand
 * the moment the owning file decodes. {@link #validateAgainstWorlds} asks whether any world on
 * this server satisfies it, which is only meaningful once the universe is up - so a consumer runs
 * it from a late audit rather than from a layer fold. The one cross-asset question
 * {@link #validateSelector} asks, whether a {@code Placement} id names a placement this server has,
 * stays silent until the placement pool has folded, so a consumer's late audit (where every caller
 * of it runs) reports it, as every other cross-asset id is reported. A placement's OWN {@code Where}
 * goes through {@link #validateForPlacement} instead, since a placement cannot follow another one.
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

    /** A {@code Placement} id no placement on this server has, so it adds no world: a WARNING. */
    public static final String UNKNOWN_PLACEMENT = "UNKNOWN_PLACEMENT";

    /**
     * A placement's own {@code Where} naming placements, which resolves to nothing (no chains): a
     * WARNING, since the file still loads and its other axes still place the NPC.
     */
    public static final String PLACEMENT_CHAIN = "PLACEMENT_CHAIN";

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
     * (typically the owning asset's id plus its field name). A {@code Placement} id no placement on
     * this server has is a WARNING ({@link #UNKNOWN_PLACEMENT}) once the placement pool has folded;
     * before that, and for an id the mod gate refused, nothing is said about it.
     */
    @Nonnull
    public static List<Finding> validateSelector(@Nullable WorldSelector selector,
            @Nonnull String contextId) {
        List<Finding> out = new ArrayList<>();
        if (selector == null) {
            return out;
        }
        reportExcludeOnly(selector, contextId, out);
        reportBlankPatterns(selector.getMatch(), "Match", contextId, out);
        reportBlankPatterns(selector.getGameplayConfig(), "GameplayConfig", contextId, out);
        reportBlankPatterns(selector.getExcludeMatch(), "ExcludeMatch", contextId, out);
        reportBlankPatterns(selector.getPlacement(), "Placement", contextId, out);
        reportUnknownPlacements(selector.getPlacement(), contextId, out);
        return out;
    }

    /**
     * Audit the {@code Where} an NPC placement stands under: {@link #validateSelector}'s shape check,
     * except that a {@code Placement} axis there is {@link #PLACEMENT_CHAIN}, a WARNING, and is
     * otherwise ignored. A placement cannot follow another placement, so that axis resolves to
     * nothing ({@link WorldSelector#withoutPlacement}), and its ids are never looked up.
     */
    @Nonnull
    public static List<Finding> validateForPlacement(@Nullable WorldSelector selector,
            @Nonnull String contextId) {
        List<Finding> out = new ArrayList<>();
        if (selector == null) {
            return out;
        }
        String[] placements = selector.getPlacement();
        if (placements == null || isAllBlank(placements)) {
            reportExcludeOnly(selector, contextId, out);
        } else {
            boolean standsNowhere = selector.withoutPlacement().hasNoPositiveAxis();
            out.add(Finding.warning(DOMAIN, PLACEMENT_CHAIN,
                    "a placement's own Where cannot name placements, so its Placement axis matches no world"
                            + (standsNowhere ? " and this placement stands nowhere" : "")
                            + "; write the worlds it stands in with Match or GameplayConfig", contextId));
        }
        reportBlankPatterns(selector.getMatch(), "Match", contextId, out);
        reportBlankPatterns(selector.getGameplayConfig(), "GameplayConfig", contextId, out);
        reportBlankPatterns(selector.getExcludeMatch(), "ExcludeMatch", contextId, out);
        return out;
    }

    /** ExcludeMatch with no positive axis to filter: the Where matches nothing. */
    private static void reportExcludeOnly(@Nonnull WorldSelector selector, @Nonnull String contextId,
            @Nonnull List<Finding> out) {
        boolean hasExcludes = selector.getExcludeMatch() != null
                && !isAllBlank(selector.getExcludeMatch());
        if (selector.hasNoPositiveAxis() && hasExcludes) {
            out.add(Finding.error(DOMAIN, "EXCLUDE_ONLY",
                    "ExcludeMatch is a filter over the positive axes, not a complement of them, so a "
                            + "Where with only ExcludeMatch matches NOTHING - add Match, GameplayConfig "
                            + "or Placement to say which worlds it applies to first", contextId));
        }
    }

    /**
     * Each {@code Placement} id no placement on this server answers to, once that can be told
     * ({@link PlacementWheres#known}): a WARNING, never an error, since the pack owning that placement
     * may simply not be installed here.
     */
    private static void reportUnknownPlacements(@Nullable String[] ids, @Nonnull String contextId,
            @Nonnull List<Finding> out) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue; // BLANK_ENTRY, above.
            }
            if (Boolean.FALSE.equals(PlacementWheres.known(id))) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_PLACEMENT,
                        "Placement names '" + id.trim() + "', which no NPC placement on this server has, so it "
                                + "adds no world; it comes back on its own if the pack owning that placement "
                                + "is installed", contextId));
            }
        }
    }

    // ==================== the describes-a-real-world check ====================

    /**
     * Report a {@code Where} that describes NONE of the worlds this server has loaded. That is the
     * misconfiguration a renamed main world produces: the file is well formed, every pattern is
     * spelled right, and the content simply never appears.
     *
     * <p>An empty {@code worlds} means "cannot tell" and reports nothing. It is never an error
     * either: a {@code Where} aimed at a world that is not running right now can be perfectly
     * correct.
     *
     * <p><b>How loudly it is reported depends on HOW the selector names its worlds</b>, because the
     * two axes carry different odds of being a mistake. A {@code Match} pattern names worlds by
     * NAME, and a name that matches nothing loaded is most often a typo or a world someone renamed
     * - worth a WARNING. A selector whose only positive axis is {@code GameplayConfig} addresses a
     * world by its gameplay-config key, which is exactly how content aims at an INSTANCE: the
     * instance's name carries a fresh uuid every time it is entered, so the key is the only stable
     * handle, and the instance is not running most of the time by design. Warning about that on
     * every boot would train a server owner to skip the line that does mean something, so it is a
     * note instead. A {@code Placement} axis is scored through each named placement's own
     * {@code Where}, exactly as at runtime.
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
        String message = "this Where matches none of the worlds currently loaded (" + worldNames(worlds)
                + "), so whatever it gates never appears - check its patterns against your "
                + "server's real world names, or ignore this if it is aimed at an instance "
                + "world that is not running";
        out.add(namesWorldsByConfigOnly(selector)
                ? Finding.info(DOMAIN, "MATCHES_NO_LOADED_WORLD", message, contextId)
                : Finding.warning(DOMAIN, "MATCHES_NO_LOADED_WORLD", message, contextId));
        return out;
    }

    /**
     * Does {@code selector} name its worlds ONLY by gameplay-config key (the instance shape)? A
     * {@code Placement} axis counts as the {@code Where} of each placement it names, so a placement
     * aimed at an instance keeps the note rather than the warning.
     */
    private static boolean namesWorldsByConfigOnly(@Nonnull WorldSelector selector) {
        String[] match = selector.getMatch();
        if (match != null && !isAllBlank(match)) {
            return false;
        }
        String[] config = selector.getGameplayConfig();
        boolean byConfig = config != null && !isAllBlank(config);
        for (WorldSelector placed : selector.placementWheres()) {
            // Each answer carries no Placement axis of its own, so this never recurses.
            if (!namesWorldsByConfigOnly(placed)) {
                return false;
            }
            byConfig = true;
        }
        return byConfig;
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
