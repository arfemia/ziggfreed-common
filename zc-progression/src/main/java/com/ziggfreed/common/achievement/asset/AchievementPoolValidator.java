package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.validation.Finding;

/**
 * Audits folded achievement content for the mistakes that produce NO error at runtime: a criterion
 * listening for a moment nothing ever fires, a reward nothing pays out, a capstone over an
 * achievement nobody authored, an id the progress format cannot store. Every one of them ships as
 * something that quietly cannot be earned, which is far harder to chase than a finding at load.
 *
 * <p>Findings are shared {@link Finding} values, so a consumer maps them into its own report
 * alongside every other validator's. Pair them with the findings
 * {@link AchievementAssetStore#resolve} returns; together they cover the whole load.
 *
 * <p><b>An unknown kind is a WARNING, never an error.</b> Whichever mod owns a kind registers it at
 * its own setup, which may run after this audit and may be a mod the author expects some servers not
 * to install. A kind that IS registered and says it produces nothing is an error, because then
 * nobody is ever going to fire it.
 */
public final class AchievementPoolValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "achievement";

    private AchievementPoolValidator() {
    }

    /**
     * Audit {@code pool} against an engine's vocabularies and storage rules - the usual call, since
     * the engine already knows all three.
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull AchievementPool pool, @Nonnull AchievementEngine engine,
            @Nullable GateKindRegistry gateKinds) {
        return validate(pool, engine.objectiveKinds(), engine.rewardKinds(), engine.store(), gateKinds);
    }

    /**
     * Audit {@code pool} against vocabularies supplied piecemeal, for a caller with no engine yet.
     * A null vocabulary means "nothing is known", and the checks that depend on it are skipped
     * rather than reporting everything as unknown.
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull AchievementPool pool,
            @Nullable ObjectiveKindRegistry objectiveKinds, @Nullable RewardKindRegistry rewardKinds,
            @Nullable AchievementProgressStore store, @Nullable GateKindRegistry gateKinds) {

        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, AchievementDefinition> entry : pool.definitions().entrySet()) {
            AchievementDefinition definition = entry.getValue();
            String id = entry.getKey();

            if (store != null && store.usesReservedDelimiter(id)) {
                out.add(Finding.error(DOMAIN, "RESERVED_ID",
                        "the achievement id uses a character the progress format reserves, so a player's "
                                + "progress cannot be stored under it; rename it without any of "
                                + AchievementProgressStore.DEFAULT_RESERVED_CHARACTERS, id));
            }

            validateShape(definition, pool, out);
            out.addAll(ContentListingAsset.chainFindings(definition.chains(), DOMAIN, id));

            validateCriteria(definition, objectiveKinds, out);
            validateRewards(definition, rewardKinds, out);
            validateRequires(definition, gateKinds, out);
        }
        return out;
    }

    /** The whole-achievement checks: is there anything to earn, and does a capstone stand on anything? */
    private static void validateShape(@Nonnull AchievementDefinition definition,
            @Nonnull AchievementPool pool, @Nonnull List<Finding> out) {

        String id = definition.id();
        Achievement achievement = definition.achievement();

        if (achievement.criteria().isEmpty() && !achievement.isMeta()) {
            out.add(Finding.warning(DOMAIN, "NO_CRITERIA",
                    "it has neither Criteria nor MetaChildren, so nothing can ever earn it", id));
        }
        if (!achievement.criteria().isEmpty() && achievement.isMeta()) {
            out.add(Finding.warning(DOMAIN, "CRITERIA_AND_META",
                    "it authors both Criteria and MetaChildren; only the MetaChildren decide when it is "
                            + "earned, so the criteria never count for anything", id));
        }
        for (String child : achievement.metaChildren()) {
            if (child.equalsIgnoreCase(id)) {
                out.add(Finding.error(DOMAIN, "META_SELF_REFERENCE",
                        "MetaChildren names the achievement itself, which can never be satisfied", id));
            } else if (pool.definition(child) == null) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_META_CHILD",
                        "MetaChildren names '" + child + "', which is not an achievement in this pool; "
                                + "nobody can ever earn it, so this one stays out of reach", id));
            }
        }
    }

    private static void validateCriteria(@Nonnull AchievementDefinition definition,
            @Nullable ObjectiveKindRegistry objectiveKinds, @Nonnull List<Finding> out) {

        String id = definition.id();
        List<ObjectiveDef> criteria = definition.achievement().criteria();
        for (int i = 0; i < criteria.size(); i++) {
            ObjectiveDef criterion = criteria.get(i);
            String where = "criterion '" + criterion.id() + "'";
            String kind = criterion.kind();

            if (kind.isBlank()) {
                out.add(Finding.error(DOMAIN, "MISSING_KIND",
                        where + " names no Kind, so nothing can ever progress it", id));
                continue;
            }
            if (criterion.amount() <= 0) {
                out.add(Finding.warning(DOMAIN, "NON_POSITIVE_AMOUNT",
                        where + " asks for " + criterion.amount() + ", so it is already met before the "
                                + "player does anything", id));
            }
            if (ObjectiveKindRegistry.STAT_THRESHOLD.equalsIgnoreCase(kind.trim())
                    && criterion.target().isBlank()) {
                out.add(Finding.warning(DOMAIN, "STAT_THRESHOLD_WITHOUT_TARGET",
                        where + " listens for " + ObjectiveKindRegistry.STAT_THRESHOLD + " but names no "
                                + "Target, so there is no stat channel to measure; give it the channel id "
                                + "the threshold is about", id));
            }
            if (objectiveKinds == null) {
                continue;
            }
            if (!objectiveKinds.isRegistered(kind)) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_KIND",
                        where + " listens for '" + kind + "', which nothing registered; it can never "
                                + "progress until whichever mod fires it is installed", id));
            } else if (!objectiveKinds.isProducible(kind)) {
                out.add(Finding.error(DOMAIN, "UNPRODUCIBLE_KIND",
                        where + " listens for '" + kind + "', which is registered as something nothing ever "
                                + "fires, so it can never progress", id));
            }
        }
    }

    private static void validateRewards(@Nonnull AchievementDefinition definition,
            @Nullable RewardKindRegistry rewardKinds, @Nonnull List<Finding> out) {
        if (rewardKinds == null) {
            return;
        }
        Achievement achievement = definition.achievement();
        reportUnknownKinds(achievement.autoRewards(), "Rewards", rewardKinds, definition.id(), out);
        reportUnknownKinds(achievement.claimRewards(), "ClaimRewards", rewardKinds, definition.id(), out);
    }

    private static void reportUnknownKinds(@Nonnull List<RewardSpec> rewards, @Nonnull String where,
            @Nonnull RewardKindRegistry rewardKinds, @Nonnull String id, @Nonnull List<Finding> out) {
        for (RewardSpec reward : rewards) {
            if (!rewardKinds.isRegistered(reward.kind())) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_REWARD_KIND",
                        where + " names '" + reward.kind() + "', which has no handler registered, so earning "
                                + "this pays out nothing for it", id));
            }
        }
    }

    private static void validateRequires(@Nonnull AchievementDefinition definition,
            @Nullable GateKindRegistry gateKinds, @Nonnull List<Finding> out) {

        String id = definition.id();
        List<GateClause> clauses = new ArrayList<>();
        clauses.add(definition.requires());
        for (GateClause clause : definition.requires().allOfOrEmpty()) {
            clauses.add(clause);
        }
        for (GateClause clause : definition.requires().anyOfOrEmpty()) {
            clauses.add(clause);
        }

        for (GateClause clause : clauses) {
            if (clause == null) {
                continue;
            }
            for (FactorCondition condition : clause.factorsOrEmpty()) {
                if (condition == null || condition.isBlank()) {
                    out.add(Finding.warning(DOMAIN, "BLANK_REQUIREMENT",
                            "a Factors entry names no factor, so it is skipped and gates nothing", id));
                }
            }
            if (gateKinds == null) {
                continue;
            }
            for (String kindId : clause.customOrEmpty().keySet()) {
                if (!gateKinds.isRegistered(kindId)) {
                    out.add(Finding.warning(DOMAIN, "UNKNOWN_GATE_KIND",
                            "Requires.Custom names '" + kindId + "', which nothing registered; the "
                                    + "requirement refuses, so this stays out of reach until whichever mod "
                                    + "owns it is installed", id));
                }
            }
        }
    }
}
