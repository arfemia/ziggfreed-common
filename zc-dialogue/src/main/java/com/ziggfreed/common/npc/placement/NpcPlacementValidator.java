package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.common.world.WorldSelectorConfig;
import com.ziggfreed.common.world.WorldSelectorDef;
import com.ziggfreed.common.world.WorldSelectorValidator;
import com.ziggfreed.common.world.WorldSelectorValidator.Issue;

/**
 * Audits authored placements for the mistakes that fail SILENTLY.
 *
 * <p>That is the whole reason this exists: almost every authoring error here produces no exception
 * and no log line, only an NPC that never appears - which is indistinguishable from "the structure
 * has not generated yet" or "I have not walked there". A missing role, a gate on a factor nobody
 * registered, an anchor naming an unregistered provider, a {@code Where} pointing at a selector
 * name no world carries: each of them is invisible at runtime and obvious here.
 *
 * <p>Findings are the neutral {@link Issue} values the world-selector validator already uses, so a
 * consumer maps them into its own reporting command without learning a second shape.
 *
 * <p>Note that several checks depend on what is REGISTERED, so they are only meaningful once every
 * mod's {@code setup()} has run. Running the audit at first player-ready rather than at plugin
 * setup is what makes an "unregistered factor" finding trustworthy.
 */
public final class NpcPlacementValidator {

    private NpcPlacementValidator() {
    }

    @Nonnull
    public static List<Issue> validateAll(@Nonnull Collection<NpcPlacementAsset> placements) {
        List<Issue> out = new ArrayList<>();
        for (NpcPlacementAsset placement : placements) {
            if (placement != null) {
                validate(placement, out);
            }
        }
        return out;
    }

    @Nonnull
    public static List<Issue> validate(@Nonnull NpcPlacementAsset placement) {
        List<Issue> out = new ArrayList<>();
        validate(placement, out);
        return out;
    }

    private static void validate(@Nonnull NpcPlacementAsset placement, @Nonnull List<Issue> out) {
        String id = placement.getId() == null ? "<unnamed>" : placement.getId();

        checkIdentity(placement, id, out);
        checkWhere(placement, id, out);
        checkAnchor(placement, id, out);
        checkRequires(placement, id, out);
        checkLimits(placement, id, out);
    }

    // ==================== identity ====================

    private static void checkIdentity(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Issue> out) {
        NpcPlacementAsset.Identity identity = placement.getIdentity();
        if (identity == null) {
            out.add(Issue.error("NO_IDENTITY",
                    "no Identity is authored, so there is no NPC to place", id));
            return;
        }
        String role = identity.getRole();
        boolean hasRole = role != null && !role.isBlank();
        boolean generates = identity.usesGeneratedRole();
        if (!hasRole && !generates) {
            out.add(Issue.error("NO_ROLE",
                    "Identity authors neither a Role nor an Appearance, so nothing can be spawned", id));
            return;
        }
        if (generates) {
            String baseRole = identity.getBaseRole();
            if (baseRole == null || baseRole.isBlank()) {
                out.add(Issue.error("NO_BASE_ROLE",
                        "Identity.Appearance is authored but Identity.BaseRole is not, so there is no role "
                                + "to clone and generate from", id));
            } else if (!NpcRoleGenerator.hasBaseRole(baseRole)) {
                out.add(Issue.warning("UNREGISTERED_BASE_ROLE",
                        "no base role is registered as '" + baseRole + "', so no role can be generated and "
                                + "this placement will not appear", id));
            }
        }
    }

    // ==================== where ====================

    private static void checkWhere(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Issue> out) {
        WorldSelector where = placement.getWhere();
        if (where == null || where.isBlank()) {
            return; // Unauthored means the "primary" selector, which is always shipped.
        }
        out.addAll(WorldSelectorValidator.validateSelector(where, id + ".Where"));

        String[] names = where.getNames();
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (name == null || name.isBlank() || isKnownSelectorName(name)) {
                continue;
            }
            out.add(Issue.warning("UNKNOWN_SELECTOR_NAME",
                    "Where names the world selector '" + name + "', which no loaded selector asset "
                            + "contributes, so this placement can never match a world", id));
        }
    }

    /** Does any loaded selector asset contribute {@code name}? */
    private static boolean isKnownSelectorName(@Nonnull String name) {
        String wanted = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (WorldSelectorDef def : WorldSelectorConfig.getInstance().all().values()) {
            if (def != null && def.names().contains(wanted)) {
                return true;
            }
        }
        return false;
    }

    // ==================== anchor ====================

    private static void checkAnchor(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Issue> out) {
        NpcPlacementAsset.Anchor anchor = placement.getAnchor();
        if (anchor == null || anchor.isBlank()) {
            out.add(Issue.error("NO_ANCHOR",
                    "no Anchor group is authored, so this placement has nowhere to stand", id));
            return;
        }

        NpcPlacementAsset.Anchor.Coords coords = anchor.getCoords();
        if (coords != null && !coords.isComplete()) {
            out.add(Issue.error("INCOMPLETE_COORDS",
                    "Anchor.Coords is authored without all three of X, Y and Z, so it resolves to nothing", id));
        }

        NpcPlacementAsset.Anchor.Structure structure = anchor.getStructure();
        if (structure != null && structure.hasNoMatcher()) {
            out.add(Issue.error("STRUCTURE_NO_MATCHER",
                    "Anchor.Structure authors none of MarkerIds, Roles or KeyContains. The match is fail-closed, "
                            + "so it anchors to nothing rather than to every marker", id));
        }

        NpcPlacementAsset.Anchor.Zone zone = anchor.getZone();
        if (zone != null && zone.isBlank()) {
            out.add(Issue.error("ZONE_NO_NAME",
                    "Anchor.Zone authors no Zone name, so it can never resolve", id));
        }

        NpcPlacementAsset.Anchor.Custom custom = anchor.getCustom();
        if (custom != null) {
            if (custom.isBlank()) {
                out.add(Issue.error("CUSTOM_NO_PROVIDER",
                        "Anchor.Custom authors no Provider, so it can never resolve", id));
            } else if (!AnchorResolverRegistry.isRegistered(custom.getProvider())) {
                out.add(Issue.warning("UNREGISTERED_ANCHOR_PROVIDER",
                        "no anchor resolver is registered for provider '" + custom.getProvider()
                                + "', so this anchor yields no position and the placement will not appear", id));
            }
        }
    }

    // ==================== requires ====================

    private static void checkRequires(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Issue> out) {
        NpcPlacementAsset.Requires requires = placement.getRequires();
        if (requires == null) {
            return;
        }
        for (PlacementCondition condition : requires.conditionsOrEmpty()) {
            if (condition == null) {
                continue;
            }
            if (condition.isBlank()) {
                out.add(Issue.warning("BLANK_CONDITION",
                        "Requires.Conditions contains an entry with no Factor, which is ignored", id));
                continue;
            }
            if (!PlacementFactorRegistry.isRegistered(condition.getFactor())) {
                out.add(Issue.warning("UNREGISTERED_FACTOR",
                        "no provider is registered for factor '" + condition.getFactor()
                                + "'. It resolves to 0 and the gate fails closed, so this placement will not "
                                + "appear until the mod that owns it is installed", id));
            }
        }
    }

    // ==================== limits ====================

    private static void checkLimits(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Issue> out) {
        NpcPlacementAsset.Limits limits = placement.getLimits();
        if (limits == null) {
            return;
        }
        Double chance = limits.getSpawnChance();
        if (chance != null && chance <= 0.0) {
            out.add(Issue.error("SPAWN_CHANCE_ZERO",
                    "Limits.SpawnChance is " + chance + ", so no position is ever used and the placement "
                            + "never appears", id));
        }
        Integer max = limits.getMaxPerWorld();
        if (max != null && max < 0) {
            out.add(Issue.warning("NEGATIVE_MAX_PER_WORLD",
                    "Limits.MaxPerWorld is negative, which reads as unlimited. Use 0 for unlimited", id));
        }
    }

    /** The selector-name check as a standalone helper (a consumer command may want just this). */
    public static boolean isSelectorNameKnown(@Nullable String name) {
        return name != null && !name.isBlank() && isKnownSelectorName(name);
    }
}
