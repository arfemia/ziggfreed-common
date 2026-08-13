package com.ziggfreed.common.quest.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.time.DurationGroup;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.validation.Finding;

/**
 * Audits folded quest content for the mistakes that produce NO error at runtime: a step listening
 * for a moment nothing ever fires, a reward nothing pays out, a prerequisite nothing can answer, an
 * id the progress format cannot store. Every one of them ships as a quest that quietly cannot be
 * finished, which is far harder to chase than a finding at load.
 *
 * <p>Findings are shared {@link Finding} values, so a consumer maps them into its own report
 * alongside every other validator's. Pair them with the findings {@link QuestAssetStore#resolve}
 * returns for the generators; together they cover the whole load.
 *
 * <p><b>An unknown kind is a WARNING, never an error.</b> Whichever mod owns a kind registers it at
 * its own setup, which may run after this audit and may be a mod the author expects some servers not
 * to install. A kind that IS registered and says it produces nothing is an error, because then
 * nobody is ever going to fire it.
 */
public final class QuestPoolValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "quest";

    private QuestPoolValidator() {
    }

    /**
     * Audit {@code pool} against an engine's vocabularies and storage rules - the usual call, since
     * the engine already knows all three.
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull QuestPool pool, @Nonnull QuestEngine engine,
            @Nullable GateKindRegistry gateKinds) {
        return validate(pool, engine.objectiveKinds(), engine.rewardKinds(), engine.store(), gateKinds);
    }

    /**
     * Audit {@code pool} against vocabularies supplied piecemeal, for a caller with no engine yet.
     * A null vocabulary means "nothing is known", and the checks that depend on it are skipped
     * rather than reporting everything as unknown.
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull QuestPool pool,
            @Nullable ObjectiveKindRegistry objectiveKinds, @Nullable RewardKindRegistry rewardKinds,
            @Nullable QuestProgressStore store, @Nullable GateKindRegistry gateKinds) {

        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, QuestDefinition> entry : pool.definitions().entrySet()) {
            QuestDefinition definition = entry.getValue();
            String id = entry.getKey();

            if (store != null && store.usesReservedDelimiter(id)) {
                out.add(Finding.error(DOMAIN, "RESERVED_ID",
                        "the quest id uses a character the progress format reserves, so a player's progress "
                                + "cannot be stored under it; rename it without any of "
                                + QuestProgressStore.DEFAULT_RESERVED_CHARACTERS, id));
            }

            validateObjectives(definition, objectiveKinds, store, out);
            validateRewards(definition, rewardKinds, out);
            validateRequires(definition, pool, gateKinds, out);

            out.addAll(ContentListingAsset.chainFindings(definition.chains(), DOMAIN, id));

            for (String other : definition.resetsOnComplete()) {
                if (pool.definition(other) == null) {
                    out.add(Finding.warning(DOMAIN, "UNKNOWN_RESET_TARGET",
                            "ResetsOnComplete names '" + other + "', which is not a quest in this pool; nothing "
                                    + "is reset unless another mod supplies it", id));
                }
            }
        }
        return out;
    }

    /**
     * Audit ONE authored {@code Repeat} block, which only the ASSET carries: the folded rule has
     * already fallen back to a documented default for anything unparseable, and a fallback is
     * exactly what an author needs telling about. Called where the asset is still in hand (the
     * fold), so the finding names the file rather than the rule.
     *
     * <p>An unknown enum value is an ERROR because the fallback changes real behaviour - when the
     * clock starts, or which window is counted. A number outside its legal range is a WARNING,
     * because the clamp lands where anybody would guess.
     *
     * @return the findings for this block, empty when there is nothing wrong or nothing authored
     */
    @Nonnull
    public static List<Finding> repeatFindings(@Nullable QuestAsset.Repeat repeat,
            @Nonnull String questId) {
        List<Finding> out = new ArrayList<>();
        if (repeat == null) {
            return out;
        }
        if (repeat.parsedCooldownFrom() == null) {
            out.add(Finding.error(DOMAIN, "REPEAT_UNKNOWN_COOLDOWN_FROM",
                    "Repeat.CooldownFrom is '" + repeat.getCooldownFrom() + "', which is neither "
                            + QuestAsset.Repeat.FROM_CLAIM + " nor " + QuestAsset.Repeat.FROM_COMPLETE
                            + "; the wait counts from " + QuestAsset.Repeat.FROM_CLAIM + " instead", questId));
        }
        DurationGroup cooldown = repeat.getCooldown();
        if (cooldown != null && cooldown.hasNegativeUnit()) {
            out.add(Finding.warning(DOMAIN, "REPEAT_NEGATIVE_COOLDOWN_UNIT",
                    "Repeat.Cooldown carries a negative unit, which adds nothing to the wait; write the "
                            + "units you want or leave the block out entirely", questId));
        }
        Integer maxCompletions = repeat.getMaxCompletions();
        if (maxCompletions != null && maxCompletions.intValue() < 0) {
            out.add(Finding.warning(DOMAIN, "REPEAT_NEGATIVE_MAX_COMPLETIONS",
                    "Repeat.MaxCompletions is negative, which reads as uncapped; use 0 to say uncapped",
                    questId));
        }
        QuestAsset.Repeat.Reset reset = repeat.getReset();
        if (reset != null) {
            validateReset(reset, questId, out);
        }
        return out;
    }

    private static void validateReset(@Nonnull QuestAsset.Repeat.Reset reset, @Nonnull String questId,
            @Nonnull List<Finding> out) {

        Quest.Repeat.Reset.Period period = reset.parsedPeriod();
        if (period == null) {
            out.add(Finding.error(DOMAIN, "REPEAT_UNKNOWN_PERIOD",
                    "Repeat.Reset.Period is '" + reset.getPeriod() + "', which is neither "
                            + QuestAsset.Repeat.Reset.PERIOD_DAILY + " nor "
                            + QuestAsset.Repeat.Reset.PERIOD_WEEKLY + "; a daily window is used instead",
                    questId));
        }
        if (reset.parsedWeekday() == null) {
            out.add(Finding.error(DOMAIN, "REPEAT_UNKNOWN_WEEKDAY",
                    "Repeat.Reset.Weekday is '" + reset.getWeekday() + "', which is not a day name; the "
                            + "window starts on Monday instead", questId));
        } else if (period == Quest.Repeat.Reset.Period.DAILY && reset.getWeekday() != null) {
            out.add(Finding.warning(DOMAIN, "REPEAT_WEEKDAY_ON_DAILY",
                    "Repeat.Reset.Weekday does nothing on a daily window; either drop it or set Period to "
                            + QuestAsset.Repeat.Reset.PERIOD_WEEKLY, questId));
        }
        Integer times = reset.getTimes();
        if (times != null && times.intValue() < 1) {
            out.add(Finding.warning(DOMAIN, "REPEAT_TIMES_NON_POSITIVE",
                    "Repeat.Reset.Times is " + times + ", which would allow nothing at all; it is treated "
                            + "as 1", questId));
        }
        Integer atMinutes = reset.getAtMinutes();
        long windowMinutes = period == Quest.Repeat.Reset.Period.WEEKLY ? 7L * 24L * 60L : 24L * 60L;
        if (atMinutes != null && (atMinutes.intValue() < 0 || atMinutes.intValue() >= windowMinutes)) {
            out.add(Finding.warning(DOMAIN, "REPEAT_AT_MINUTES_OUT_OF_RANGE",
                    "Repeat.Reset.AtMinutes is " + atMinutes + ", which is outside one window; it wraps "
                            + "round into the range 0.." + (windowMinutes - 1), questId));
        }
    }

    private static void validateObjectives(@Nonnull QuestDefinition definition,
            @Nullable ObjectiveKindRegistry objectiveKinds, @Nullable QuestProgressStore store,
            @Nonnull List<Finding> out) {

        String id = definition.id();
        Quest quest = definition.quest();
        if (quest.objectives().isEmpty()) {
            out.add(Finding.warning(DOMAIN, "NO_OBJECTIVES",
                    "the quest has no steps, so it counts as finished the instant it is taken", id));
            return;
        }

        for (ObjectiveDef objective : quest.objectives()) {
            String kind = objective.kind();
            if (kind.isBlank()) {
                out.add(Finding.error(DOMAIN, "MISSING_KIND",
                        "step '" + objective.id() + "' names no Kind, so nothing can ever progress it", id));
                continue;
            }
            if (store != null && store.usesReservedDelimiter(objective.id())) {
                out.add(Finding.error(DOMAIN, "RESERVED_ID",
                        "step id '" + objective.id() + "' uses a character the progress format reserves, so "
                                + "its progress cannot be stored; rename it without any of "
                                + QuestProgressStore.DEFAULT_RESERVED_CHARACTERS, id));
            }
            if (objective.amount() <= 0) {
                out.add(Finding.warning(DOMAIN, "NON_POSITIVE_AMOUNT",
                        "step '" + objective.id() + "' asks for " + objective.amount() + ", so it is already "
                                + "done the moment the quest starts", id));
            }
            if (objectiveKinds != null) {
                if (!objectiveKinds.isRegistered(kind)) {
                    out.add(Finding.warning(DOMAIN, "UNKNOWN_KIND",
                            "step '" + objective.id() + "' listens for '" + kind + "', which nothing registered; "
                                    + "it can never progress until whichever mod fires it is installed", id));
                } else if (!objectiveKinds.isProducible(kind)) {
                    out.add(Finding.error(DOMAIN, "UNPRODUCIBLE_KIND",
                            "step '" + objective.id() + "' listens for '" + kind + "', which is registered as "
                                    + "something nothing ever fires, so it can never progress", id));
                }
            }

            if (ObjectiveKindRegistry.STAT_THRESHOLD.equalsIgnoreCase(kind.trim())
                    && objective.target().isBlank()) {
                out.add(Finding.warning(DOMAIN, "STAT_THRESHOLD_WITHOUT_TARGET",
                        "step '" + objective.id() + "' listens for "
                                + ObjectiveKindRegistry.STAT_THRESHOLD + " but names no Target, so there "
                                + "is no stat channel to measure; give it the channel id the threshold is "
                                + "about", id));
            }

            // A hand-in with a BLANK target is the report-back shape the engine documents and
            // supports (canDeliverTurnInAt: nothing to hand over, so it completes on the
            // interaction), which is what "go and tell them you are done" is authored as. It is
            // therefore not a finding at all - reporting it would tell an author to break the very
            // shape the engine offers them.
            boolean turnIn = QuestObjectiveAsset.HAND_IN_KIND.equalsIgnoreCase(kind.trim());
            if (!turnIn && objective.turnInLockId() != null) {
                out.add(Finding.warning(DOMAIN, "TURN_IN_ON_OTHER_KIND",
                        "step '" + objective.id() + "' names a hand-in place but is not a "
                                + QuestObjectiveAsset.HAND_IN_KIND + " step, "
                                + "so the place is never consulted", id));
            }
        }
    }

    private static void validateRewards(@Nonnull QuestDefinition definition,
            @Nullable RewardKindRegistry rewardKinds, @Nonnull List<Finding> out) {
        if (rewardKinds == null) {
            return;
        }
        for (RewardSpec reward : definition.quest().rewards()) {
            if (!rewardKinds.isRegistered(reward.kind())) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_REWARD_KIND",
                        "the reward '" + reward.kind() + "' has no handler registered, so finishing the quest "
                                + "pays out nothing for it", definition.id()));
            }
        }
    }

    private static void validateRequires(@Nonnull QuestDefinition definition, @Nonnull QuestPool pool,
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
            for (String prerequisite : clause.questsOrEmpty()) {
                if (prerequisite != null && !prerequisite.isBlank() && pool.definition(prerequisite) == null) {
                    out.add(Finding.warning(DOMAIN, "UNKNOWN_PREREQUISITE",
                            "Requires.Quests names '" + prerequisite + "', which is not a quest in this pool; "
                                    + "nobody can ever have finished it, so this quest stays locked", id));
                }
            }
            if (gateKinds != null) {
                for (String kindId : clause.customOrEmpty().keySet()) {
                    if (!gateKinds.isRegistered(kindId)) {
                        out.add(Finding.warning(DOMAIN, "UNKNOWN_GATE_KIND",
                                "Requires.Custom names '" + kindId + "', which nothing registered; the "
                                        + "requirement refuses, so this quest stays locked until whichever mod "
                                        + "owns it is installed", id));
                    }
                }
            }
        }
    }
}
