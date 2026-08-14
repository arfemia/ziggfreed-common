package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.WhereValidator;
import com.ziggfreed.common.world.WorldIdentity;
import com.ziggfreed.common.world.WorldSelector;

/**
 * Audits authored placements for the mistakes that fail SILENTLY.
 *
 * <p>That is the whole reason this exists: almost every authoring error here produces no exception
 * and no log line, only an NPC that never appears - which is indistinguishable from "the structure
 * has not generated yet" or "I have not walked there". A missing role, a gate on a factor nobody
 * registered, an anchor naming an unregistered provider, a {@code Where} whose patterns describe
 * no world on this server: each of them is invisible at runtime and obvious here.
 *
 * <p>Findings are shared {@link Finding} values, so a consumer maps them into its own reporting
 * command alongside every other validator's.
 *
 * <p><b>Two surfaces, because the checks answer two different kinds of question.</b>
 * <ul>
 *   <li>{@link #auditFileLocal} asks only what a placement file says about ITSELF - shape, spelling
 *       and self-contradiction. Every answer is already in hand the moment the file decodes, so
 *       this is the half that is safe to run on each layer fold.</li>
 *   <li>{@link #audit} adds the CROSS-ASSET half: the checks that ask another store or an open
 *       registry whether an id an author named exists. Those
 *       answers are only trustworthy once every store has folded, every mod's {@code setup()} has
 *       run and the universe is up, so run the full audit at first player-ready. Run earlier, it
 *       reports whatever had not loaded yet.</li>
 * </ul>
 * A check that CANNOT tell (no server, no loaded assets, an empty vocabulary) reports nothing
 * rather than guessing, which is what keeps the cross-asset half silent in a unit JVM.
 */
public final class NpcPlacementValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "placement";

    private NpcPlacementValidator() {
    }

    // ==================== the full audit ====================

    /** Audit every placement in {@code placements}, cross-asset checks included. */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<NpcPlacementAsset> placements) {
        List<Finding> out = new ArrayList<>();
        for (NpcPlacementAsset placement : placements) {
            if (placement != null) {
                validateBoth(placement, out);
            }
        }
        return out;
    }

    /** Audit ONE placement, cross-asset checks included. */
    @Nonnull
    public static List<Finding> audit(@Nonnull NpcPlacementAsset placement) {
        List<Finding> out = new ArrayList<>();
        validateBoth(placement, out);
        return out;
    }

    private static void validateBoth(@Nonnull NpcPlacementAsset placement, @Nonnull List<Finding> out) {
        String id = sourceId(placement);
        validateFileLocal(placement, id, out);
        validateCrossAsset(placement, id, out);
    }

    // ==================== the file-local audit ====================

    /**
     * Audit every placement in {@code placements} on its own terms only: the findings whose whole
     * answer is inside the file. Nothing here reads another store, a registry or a loaded asset, so
     * it is stable however much of the server is still coming up.
     */
    @Nonnull
    public static List<Finding> auditFileLocal(@Nonnull Collection<NpcPlacementAsset> placements) {
        List<Finding> out = new ArrayList<>();
        for (NpcPlacementAsset placement : placements) {
            if (placement != null) {
                validateFileLocal(placement, sourceId(placement), out);
            }
        }
        return out;
    }

    /** Audit ONE placement on its own terms only. See {@link #auditFileLocal(Collection)}. */
    @Nonnull
    public static List<Finding> auditFileLocal(@Nonnull NpcPlacementAsset placement) {
        List<Finding> out = new ArrayList<>();
        validateFileLocal(placement, sourceId(placement), out);
        return out;
    }

    @Nonnull
    private static String sourceId(@Nonnull NpcPlacementAsset placement) {
        return placement.getId() == null ? "<unnamed>" : placement.getId();
    }

    private static void validateFileLocal(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        checkIdentityShape(placement, id, out);
        checkWhereShape(placement, id, out);
        checkAnchorShape(placement, id, out);
        checkRequiresShape(placement, id, out);
        checkLimits(placement, id, out);
        checkBindingKeys(placement, id, out);
    }

    private static void validateCrossAsset(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        checkWhereMatchesALoadedWorld(placement, id, out);
        checkAnchorProvider(placement, id, out);
        checkRequiresFactors(placement, id, out);
        checkChanceFormulaFactors(placement, id, out);
        checkBindingNamespaces(placement, id, out);
    }

    // ==================== identity ====================

    private static void checkIdentityShape(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Identity identity = placement.getIdentity();
        if (identity == null) {
            out.add(Finding.error(DOMAIN, "NO_IDENTITY",
                    "no Identity is authored, so there is no NPC to place", id));
            return;
        }
        if (!identity.namesRole()) {
            out.add(Finding.error(DOMAIN, "NO_ROLE",
                    "Identity authors no Role, so there is no NPC role to spawn. Name the role that describes "
                            + "this character, which any pack on the server may ship", id));
        }
    }

    // ==================== where ====================

    /** The shape of the embedded {@code Where} group, which the world layer owns the rules for. */
    private static void checkWhereShape(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        WorldSelector where = placement.getWhere();
        if (where == null || where.isBlank()) {
            return; // Unauthored means the world named "default", which needs no checking.
        }
        out.addAll(WhereValidator.validateSelector(where, id + ".Where"));
    }

    /**
     * Does the authored {@code Where} describe any world this server actually has? That question
     * belongs to the world layer, and it can only be answered once the universe is up - which is
     * why it sits in the cross-asset half rather than beside the shape check.
     */
    private static void checkWhereMatchesALoadedWorld(@Nonnull NpcPlacementAsset placement,
            @Nonnull String id, @Nonnull List<Finding> out) {
        WorldSelector where = placement.getWhere();
        if (where == null || where.isBlank()) {
            return;
        }
        out.addAll(WhereValidator.validateAgainstWorlds(where, id + ".Where",
                WorldIdentity.loadedWorlds()));
    }

    // ==================== anchor ====================

    private static void checkAnchorShape(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
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
        if (custom != null && custom.isBlank()) {
            out.add(Finding.error(DOMAIN, "CUSTOM_NO_PROVIDER",
                    "Anchor.Custom authors no Provider, so it can never resolve", id));
        }
    }

    /** Whether anybody has registered the custom anchor provider this file names. */
    private static void checkAnchorProvider(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Anchor anchor = placement.getAnchor();
        if (anchor == null || anchor.isBlank()) {
            return;
        }
        NpcPlacementAsset.Anchor.Custom custom = anchor.getCustom();
        if (custom == null || custom.isBlank()) {
            return;
        }
        if (!AnchorResolverRegistry.isRegistered(custom.getProvider())) {
            out.add(Finding.warning(DOMAIN, "UNREGISTERED_ANCHOR_PROVIDER",
                    "no anchor resolver is registered for provider '" + custom.getProvider()
                            + "', so this anchor yields no position and the placement will not appear", id));
        }
    }

    // ==================== requires ====================

    private static void checkRequiresShape(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Requires requires = placement.getRequires();
        if (requires == null) {
            return;
        }
        for (FactorCondition condition : requires.conditionsOrEmpty()) {
            if (condition != null && condition.isBlank()) {
                out.add(Finding.warning(DOMAIN, "BLANK_CONDITION",
                        "Requires.Conditions contains an entry with no Factor, which is ignored", id));
            }
        }
    }

    /** Whether anybody has registered a provider for each factor the gate reads. */
    private static void checkRequiresFactors(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Requires requires = placement.getRequires();
        if (requires == null) {
            return;
        }
        for (FactorCondition condition : requires.conditionsOrEmpty()) {
            if (condition == null || condition.isBlank()) {
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
        checkChanceFormulaShape(limits, id, out);
        Integer max = limits.getMaxPerWorld();
        if (max != null && max < 0) {
            out.add(Finding.warning(DOMAIN, "NEGATIVE_MAX_PER_WORLD",
                    "Limits.MaxPerWorld is negative, which reads as unlimited. Use 0 for unlimited", id));
        }
    }

    /**
     * The chance formula's own contradictions: a group that says nothing, and which source is
     * actually used when both are authored.
     */
    private static void checkChanceFormulaShape(@Nonnull NpcPlacementAsset.Limits limits, @Nonnull String id,
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
    }

    /**
     * The terms nobody can answer. A term on an unregistered factor is only ever a WARNING, because
     * on the value side it contributes 0 rather than voiding the whole chance.
     */
    private static void checkChanceFormulaFactors(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        NpcPlacementAsset.Limits limits = placement.getLimits();
        if (limits == null) {
            return;
        }
        FactorFormula formula = limits.getChanceFormula();
        if (formula == null || formula.isEmpty()) {
            return;
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

    /** A channel key with no owner in it, which is answerable off the key alone. */
    private static void checkBindingKeys(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        Map<String, PlacementBinding> bindings = bindingsOf(placement);
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
    }

    /** Whether anybody has claimed each namespace the bindings hand a payload to. */
    private static void checkBindingNamespaces(@Nonnull NpcPlacementAsset placement, @Nonnull String id,
            @Nonnull List<Finding> out) {
        Map<String, PlacementBinding> bindings = bindingsOf(placement);
        if (bindings.isEmpty()) {
            return;
        }
        for (String namespace : NpcPlacementBindings.byNamespace(bindings, id).keySet()) {
            if (!NpcPlacementBindings.isRegistered(namespace)) {
                out.add(Finding.warning(DOMAIN, "UNCLAIMED_BINDING_NAMESPACE",
                        "Interact.Bindings authors the namespace '" + namespace + "', but no handler has "
                                + "registered it, so those bindings are ignored at press-F", id));
            }
        }
    }

    @Nonnull
    private static Map<String, PlacementBinding> bindingsOf(@Nonnull NpcPlacementAsset placement) {
        NpcPlacementAsset.Interact interact = placement.getInteract();
        return interact == null ? Map.of() : interact.getBindings();
    }

}
