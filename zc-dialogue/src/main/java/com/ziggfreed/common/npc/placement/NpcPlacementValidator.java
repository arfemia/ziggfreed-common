package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.common.world.WorldSelectorValidator;

/**
 * Audits authored placements for the mistakes that fail SILENTLY.
 *
 * <p>That is the whole reason this exists: almost every authoring error here produces no exception
 * and no log line, only an NPC that never appears - which is indistinguishable from "the structure
 * has not generated yet" or "I have not walked there". A missing role, a gate on a factor nobody
 * registered, an anchor naming an unregistered provider, a {@code Where} pointing at a selector
 * name no world carries: each of them is invisible at runtime and obvious here.
 *
 * <p>Findings are shared {@link Finding} values, so a consumer maps them into its own reporting
 * command alongside every other validator's.
 *
 * <p>Note that several checks depend on what is REGISTERED, so they are only meaningful once every
 * mod's {@code setup()} has run. Running the audit at first player-ready rather than at plugin
 * setup is what makes an "unregistered factor" finding trustworthy.
 */
public final class NpcPlacementValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "placement";

    private NpcPlacementValidator() {
    }

    /** Audit every placement in {@code placements}. */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<NpcPlacementAsset> placements) {
        List<Finding> out = new ArrayList<>();
        for (NpcPlacementAsset placement : placements) {
            if (placement != null) {
                validate(placement, out);
            }
        }
        return out;
    }

    /** Audit ONE placement. */
    @Nonnull
    public static List<Finding> audit(@Nonnull NpcPlacementAsset placement) {
        List<Finding> out = new ArrayList<>();
        validate(placement, out);
        return out;
    }

    private static void validate(@Nonnull NpcPlacementAsset placement, @Nonnull List<Finding> out) {
        String id = placement.getId() == null ? "<unnamed>" : placement.getId();

        checkIdentity(placement, id, out);
        checkWhere(placement, id, out);
        checkAnchor(placement, id, out);
        checkRequires(placement, id, out);
        checkLimits(placement, id, out);
        checkInteract(placement, id, out);
    }

    // ==================== identity ====================

    private static void checkIdentity(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Identity identity = placement.getIdentity();
        if (identity == null) {
            out.add(Finding.error(DOMAIN, "NO_IDENTITY",
                    "no Identity is authored, so there is no NPC to place", id));
            return;
        }
        String role = identity.getRole();
        boolean hasRole = role != null && !role.isBlank();
        boolean generates = identity.usesGeneratedRole();
        if (!hasRole && !generates) {
            out.add(Finding.error(DOMAIN, "NO_ROLE",
                    "Identity authors neither a Role nor an Appearance, so nothing can be spawned", id));
            return;
        }
        if (generates) {
            checkTemplate(identity, id, out);
        }
        checkAppearance(identity.getAppearance(), id, out);
    }

    // ==================== the template a generated role varies ====================

    /**
     * The template side of a generated role. A native variant only carries overrides, so both of
     * the mistakes here are silent at runtime: a {@code Reference} to a role nobody ships produces
     * nothing, and a key the template never declared makes the engine refuse the whole variant, not
     * just that key.
     *
     * <p>Both answers come from the engine's own loaded roles, so both go quiet when there is
     * nothing to ask (see {@link RoleTemplates}). The unknown-template finding is a WARNING because
     * a pack may still be loading; the unoffered-key finding is an ERROR because it is a mismatch
     * between two files that are both already here.
     */
    private static void checkTemplate(@Nonnull NpcPlacementAsset.Identity identity, @Nonnull String id,
            @Nonnull List<Finding> out) {
        String template = identity.getBaseRole();
        if (template == null || template.isBlank()) {
            out.add(Finding.error(DOMAIN, "NO_BASE_ROLE",
                    "Identity.Appearance is authored but Identity.BaseRole is not, so there is no template role "
                            + "to build a variant of", id));
            return;
        }
        if (Boolean.FALSE.equals(RoleTemplates.templateExists(template))) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_TEMPLATE",
                    "Identity.BaseRole names the template role '" + template + "', which no loaded pack provides, "
                            + "so no role can be generated and this placement will not appear. Check the spelling, "
                            + "or install the pack that ships it", id));
            return;
        }
        for (String key : RoleTemplates.unparameterizedKeys(template, NpcRoleGenerator.authoredModifyKeys(identity, id))) {
            out.add(Finding.error(DOMAIN, "MODIFY_KEY_NOT_PARAMETERIZED",
                    "the template role '" + template + "' does not declare '" + key + "' in its Parameters block, "
                            + "so this placement cannot override it. Add \"" + key + "\" to that template's "
                            + "Parameters and bind it into the body, or stop authoring the field that emits it", id));
        }
    }

    // ==================== appearance ====================

    /**
     * The appearance group's own findings. {@code Model} and {@code Base} are the one exclusive
     * choice in the schema, and every other knob only means something beside {@code Base}.
     *
     * <p>A particle's {@code TargetNodeName} is deliberately NOT checked. A bone name lives on the
     * model's mesh, which nothing on the server can enumerate, so there is no honest way to tell a
     * correct name from a typo here. A wrong one costs that one particle, silently.
     */
    private static void checkAppearance(@Nullable AppearanceSpec appearance, @Nonnull String id,
            @Nonnull List<Finding> out) {
        if (appearance == null) {
            return;
        }
        if (appearance.hasBothForms()) {
            out.add(Finding.error(DOMAIN, "APPEARANCE_MODEL_AND_BASE",
                    "Identity.Appearance authors both Model and Base. Model uses a model as it is and Base "
                            + "clones one to re-dress, so authoring both leaves it ambiguous which look is meant. "
                            + "Keep Model to use an existing look, or Base plus the overrides to build a variant",
                    id));
        } else if (!appearance.hasBase() && appearance.hasCloneOverrides()) {
            out.add(Finding.warning(DOMAIN, "APPEARANCE_OVERRIDE_WITHOUT_BASE",
                    "Identity.Appearance authors Texture, a gradient, Scale or Particles without a Base to "
                            + "clone, so those overrides have nothing to apply to and are ignored. Author Base "
                            + "with the model you want to re-dress", id));
        }

        String modelId = appearance.hasBase() ? appearance.getBase() : appearance.getModel();
        if (Boolean.FALSE.equals(modelExists(modelId))) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_APPEARANCE_MODEL",
                    "no Model asset is loaded with the id '" + modelId + "', so this NPC has no look to render. "
                            + "Check the spelling against the model files your packs ship", id));
        }

        for (AppearanceSpec.ParticleSpec particle : appearance.particlesOrEmpty()) {
            if (particle == null || particle.isBlank()) {
                continue;
            }
            if (Boolean.FALSE.equals(particleSystemExists(particle.getSystemId()))) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_PARTICLE_SYSTEM",
                        "no particle system is loaded with the id '" + particle.getSystemId()
                                + "', so that entry renders nothing", id));
            }
        }
    }

    /** Is a Model asset loaded under {@code modelId}? See {@link #presenceIn}. */
    @Nullable
    private static Boolean modelExists(@Nullable String modelId) {
        try {
            return presenceIn(ModelAsset.getAssetMap(), modelId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Is a particle system loaded under {@code systemId}? See {@link #presenceIn}. */
    @Nullable
    private static Boolean particleSystemExists(@Nullable String systemId) {
        try {
            return presenceIn(ParticleSystem.getAssetMap(), systemId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether {@code assetId} is in {@code map}. {@code null} means the question cannot be answered
     * from here - the asset store is not up, or holds nothing yet - and a caller reports nothing on
     * that answer rather than guessing, so running the audit before assets load costs findings
     * rather than inventing them.
     */
    @Nullable
    private static Boolean presenceIn(@Nullable DefaultAssetMap<String, ?> map, @Nullable String assetId) {
        if (assetId == null || assetId.isBlank() || map == null || map.getAssetCount() <= 0) {
            return null;
        }
        return map.getAsset(assetId) != null;
    }

    // ==================== where ====================

    private static void checkWhere(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        WorldSelector where = placement.getWhere();
        if (where == null || where.isBlank()) {
            return; // Unauthored means the "default" selector, which is always shipped.
        }
        out.addAll(WorldSelectorValidator.validateSelector(where, id + ".Where"));
        // The name-is-known question belongs to the selector layer, not to placement: it is the
        // same question a dialogue's World condition asks, and one answer keeps them agreeing.
        out.addAll(WorldSelectorValidator.validateNames(where.getNames(), "Where",
                id, WorldSelectorValidator.knownNames()));
    }

    // ==================== anchor ====================

    private static void checkAnchor(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Anchor anchor = placement.getAnchor();
        if (anchor == null || anchor.isBlank()) {
            out.add(Finding.error(DOMAIN, "NO_ANCHOR",
                    "no Anchor group is authored, so this placement has nowhere to stand", id));
            return;
        }

        NpcPlacementAsset.Anchor.Coords coords = anchor.getCoords();
        if (coords != null && !coords.isComplete()) {
            out.add(Finding.error(DOMAIN, "INCOMPLETE_COORDS",
                    "Anchor.Coords is authored without all three of X, Y and Z, so it resolves to nothing", id));
        }

        NpcPlacementAsset.Anchor.Structure structure = anchor.getStructure();
        if (structure != null && structure.hasNoMatcher()) {
            out.add(Finding.error(DOMAIN, "STRUCTURE_NO_MATCHER",
                    "Anchor.Structure authors none of MarkerIds, Roles or KeyContains. The match is fail-closed, "
                            + "so it anchors to nothing rather than to every marker", id));
        }

        NpcPlacementAsset.Anchor.Zone zone = anchor.getZone();
        if (zone != null && zone.isBlank()) {
            out.add(Finding.error(DOMAIN, "ZONE_NO_NAME",
                    "Anchor.Zone authors no Zone name, so it can never resolve", id));
        }

        NpcPlacementAsset.Anchor.Custom custom = anchor.getCustom();
        if (custom != null) {
            if (custom.isBlank()) {
                out.add(Finding.error(DOMAIN, "CUSTOM_NO_PROVIDER",
                        "Anchor.Custom authors no Provider, so it can never resolve", id));
            } else if (!AnchorResolverRegistry.isRegistered(custom.getProvider())) {
                out.add(Finding.warning(DOMAIN, "UNREGISTERED_ANCHOR_PROVIDER",
                        "no anchor resolver is registered for provider '" + custom.getProvider()
                                + "', so this anchor yields no position and the placement will not appear", id));
            }
        }
    }

    // ==================== requires ====================

    private static void checkRequires(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Requires requires = placement.getRequires();
        if (requires == null) {
            return;
        }
        for (FactorCondition condition : requires.conditionsOrEmpty()) {
            if (condition == null) {
                continue;
            }
            if (condition.isBlank()) {
                out.add(Finding.warning(DOMAIN, "BLANK_CONDITION",
                        "Requires.Conditions contains an entry with no Factor, which is ignored", id));
                continue;
            }
            if (!PlacementFactorRegistry.isRegistered(condition.getFactor())) {
                out.add(Finding.warning(DOMAIN, "UNREGISTERED_FACTOR",
                        "no provider is registered for factor '" + condition.getFactor()
                                + "'. It cannot resolve and the gate fails closed, so this placement will not "
                                + "appear until the mod that owns it is installed", id));
            }
        }
    }

    // ==================== limits ====================

    private static void checkLimits(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Limits limits = placement.getLimits();
        if (limits == null) {
            return;
        }
        Double chance = limits.getSpawnChance();
        if (chance != null && chance <= 0.0 && !limits.hasChanceFormula()) {
            out.add(Finding.error(DOMAIN, "SPAWN_CHANCE_ZERO",
                    "Limits.SpawnChance is " + chance + ", so no position is ever used and the placement "
                            + "never appears", id));
        }
        checkChanceFormula(limits, id, out);
        Integer max = limits.getMaxPerWorld();
        if (max != null && max < 0) {
            out.add(Finding.warning(DOMAIN, "NEGATIVE_MAX_PER_WORLD",
                    "Limits.MaxPerWorld is negative, which reads as unlimited. Use 0 for unlimited", id));
        }
    }

    /**
     * The chance formula's own findings: which source is actually used when both are authored, and
     * the terms nobody can answer. A term on an unregistered factor is only ever a WARNING, because
     * on the value side it contributes 0 rather than voiding the whole chance.
     */
    private static void checkChanceFormula(@Nonnull NpcPlacementAsset.Limits limits, @Nonnull String id,
            @Nonnull List<Finding> out) {
        FactorFormula formula = limits.getChanceFormula();
        if (formula == null) {
            return;
        }
        if (formula.isEmpty()) {
            out.add(Finding.warning(DOMAIN, "EMPTY_CHANCE_FORMULA",
                    "Limits.ChanceFormula is authored but carries neither a Base nor a usable term, so it says "
                            + "nothing and the plain SpawnChance is used instead", id));
            return;
        }
        if (limits.getSpawnChance() != null) {
            // Both authored is not a defect: the formula is read and the plain number is simply
            // left over. That is a remark about clarity, not something that stops working.
            out.add(Finding.info(DOMAIN, "CHANCE_FORMULA_AND_SCALAR",
                    "Limits authors both SpawnChance and ChanceFormula. The formula is what is used; the number "
                            + "is left as a note of what was intended. Remove one to make the file say what it "
                            + "does", id));
        }
        for (FactorFormula.Term term : formula.termsOrEmpty()) {
            if (term == null || term.isBlank()) {
                continue;
            }
            if (!PlacementFactorRegistry.isRegistered(term.getFactor())) {
                out.add(Finding.warning(DOMAIN, "UNREGISTERED_FACTOR",
                        "Limits.ChanceFormula reads the factor '" + term.getFactor() + "', which no provider is "
                                + "registered for. It contributes 0 to the chance rather than voiding it, so "
                                + "author Base for the chance the placement must have without it", id));
            }
        }
    }

    // ==================== interact ====================

    private static void checkInteract(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Interact interact = placement.getInteract();
        if (interact == null) {
            return;
        }
        Map<String, PlacementBinding> bindings = interact.getBindings();
        if (bindings.isEmpty()) {
            return;
        }

        for (String channel : bindings.keySet()) {
            if (channel == null) {
                continue;
            }
            int colon = channel.indexOf(':');
            if (colon <= 0 || channel.substring(0, colon).isBlank()) {
                out.add(Finding.warning(DOMAIN, "BINDING_KEY_NO_NAMESPACE",
                        "Interact.Bindings key '" + channel + "' has no 'namespace:channel' prefix, so it has "
                                + "no owner and is dropped at every press-F", id));
            }
        }

        for (String namespace : NpcPlacementBindings.byNamespace(bindings, id).keySet()) {
            if (!NpcPlacementBindings.isRegistered(namespace)) {
                out.add(Finding.warning(DOMAIN, "UNCLAIMED_BINDING_NAMESPACE",
                        "Interact.Bindings authors the namespace '" + namespace + "', but no handler has "
                                + "registered it, so those bindings are ignored at press-F", id));
            }
        }
    }

    /**
     * The selector-name check as a standalone helper (a consumer command may want just this).
     * Forwards to the selector layer, which owns the vocabulary.
     */
    public static boolean isSelectorNameKnown(@Nullable String name) {
        return WorldSelectorValidator.isKnownName(name);
    }
}
