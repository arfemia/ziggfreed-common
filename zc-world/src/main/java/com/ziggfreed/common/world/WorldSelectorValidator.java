package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.validation.Finding;

/**
 * Audits authored world selectors for the mistakes that fail SILENTLY at runtime - a selector
 * that names nothing, or that names something it can never match, produces no error anywhere; the
 * content bound to it simply never appears.
 *
 * <p>Two entry points, because there are two authoring surfaces: {@link #validate(WorldSelectorDef)}
 * for a {@link WorldSelectorAsset} file, and {@link #validateSelector} for a {@link WorldSelector}
 * group embedded in some other asset. Findings are shared {@link Finding} values, so a consumer maps
 * them into its own reporting framework alongside every other validator's.
 */
public final class WorldSelectorValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "worldselector";

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
