package com.ziggfreed.common.quest.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.time.DurationGroup;
import com.ziggfreed.common.util.PeriodMath;
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

    /** What one piece of this content is CALLED in a message written for the author. */
    private static final String NOUN = "quest";

    /**
     * Does this server declare a character under this id? The audit cannot answer that itself - who
     * stands where is declared a layer above this module - so a caller that CAN answer supplies this
     * and gets the finding, while one that cannot passes null and the check is skipped.
     *
     * <p>It is deliberately consulted for a WARNING only. An id nothing declares may belong to a mod
     * the author expects some servers not to install, and a convention-named character need not be
     * enumerable at all, so a probe answering no is a reason to tell somebody, never to refuse
     * content.
     */
    @FunctionalInterface
    public interface NpcIdProbe {

        /** True when something on this server answers to {@code npcId}. */
        boolean declares(@Nonnull String npcId);
    }

    private QuestPoolValidator() {
    }

    /**
     * Audit {@code pool} against an engine's vocabularies and storage rules - the usual call, since
     * the engine already knows all three.
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull QuestPool pool, @Nonnull QuestEngine engine,
            @Nullable GateKindRegistry gateKinds) {
        return validate(pool, engine, gateKinds, null);
    }

    /** {@link #validate(QuestPool, QuestEngine, GateKindRegistry)} plus the character-id probe. */
    @Nonnull
    public static List<Finding> validate(@Nonnull QuestPool pool, @Nonnull QuestEngine engine,
            @Nullable GateKindRegistry gateKinds, @Nullable NpcIdProbe npcIds) {
        return validate(pool, engine.objectiveKinds(), engine.rewardKinds(), engine.store(), gateKinds,
                npcIds);
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
        return validate(pool, objectiveKinds, rewardKinds, store, gateKinds, null);
    }

    /** The piecemeal form plus the character-id probe; every other entry point lands here. */
    @Nonnull
    public static List<Finding> validate(@Nonnull QuestPool pool,
            @Nullable ObjectiveKindRegistry objectiveKinds, @Nullable RewardKindRegistry rewardKinds,
            @Nullable QuestProgressStore store, @Nullable GateKindRegistry gateKinds,
            @Nullable NpcIdProbe npcIds) {

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
            validateTurnInAt(definition, npcIds, out);

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

        QuestAsset.Repeat.Reset.Period period = reset.parsedPeriod();
        if (period == null) {
            out.add(Finding.error(DOMAIN, "REPEAT_UNKNOWN_PERIOD",
                    "Repeat.Reset.Period is '" + reset.getPeriod() + "', which is neither "
                            + QuestAsset.Repeat.Reset.PERIOD_DAILY + " nor "
                            + QuestAsset.Repeat.Reset.PERIOD_WEEKLY + "; it is ignored, and the window is "
                            + "Every when that is authored, else one day", questId));
        }
        DurationGroup every = reset.getEvery();
        if (every != null) {
            if (every.hasNegativeUnit()) {
                out.add(Finding.warning(DOMAIN, "REPEAT_NEGATIVE_EVERY_UNIT",
                        "Repeat.Reset.Every carries a negative unit, which adds nothing to the window; write "
                                + "the units you want or leave the group out entirely", questId));
            }
            if (every.totalMs() <= 0L) {
                out.add(Finding.error(DOMAIN, "REPEAT_EVERY_EMPTY",
                        "Repeat.Reset.Every adds up to no time at all, so a daily window is used instead; "
                                + "write the units the window should last, or drop the group", questId));
            } else if (reset.getPeriod() != null) {
                out.add(Finding.warning(DOMAIN, "REPEAT_EVERY_AND_PERIOD",
                        "Repeat.Reset authors both Every and Period; Every wins, so drop Period", questId));
            }
        }
        if (reset.parsedWeekday() == null) {
            out.add(Finding.error(DOMAIN, "REPEAT_UNKNOWN_WEEKDAY",
                    "Repeat.Reset.Weekday is '" + reset.getWeekday() + "', which is not a day name; the "
                            + "window starts on Monday instead", questId));
        } else if (reset.getWeekday() != null && !reset.toReset().weekAligned()) {
            out.add(Finding.warning(DOMAIN, "REPEAT_WEEKDAY_ON_DAILY",
                    "Repeat.Reset.Weekday only takes part when the window is a whole number of weeks; either "
                            + "drop it or make the window Weekly (or Every {Weeks: N})", questId));
        }
        Integer times = reset.getTimes();
        if (times != null && times.intValue() < 1) {
            out.add(Finding.warning(DOMAIN, "REPEAT_TIMES_NON_POSITIVE",
                    "Repeat.Reset.Times is " + times + ", which would allow nothing at all; it is treated "
                            + "as 1", questId));
        }
        Integer atMinutes = reset.getAtMinutes();
        long windowMinutes = Math.max(1L, reset.periodMs() / PeriodMath.MINUTE_MS);
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

    /**
     * The collection site: both ways of naming one that nobody can ever be. Each is a WARNING, for
     * the same reason an unknown objective kind is - the character may belong to a mod this server
     * has not installed, and the audit cannot tell that apart from a typo.
     */
    private static void validateTurnInAt(@Nonnull QuestDefinition definition,
            @Nullable NpcIdProbe npcIds, @Nonnull List<Finding> out) {

        QuestTurnInSite site = definition.turnInAt();
        if (site == null || site.isAcceptSite()) {
            return;
        }
        String id = definition.id();
        String required = site.id();
        if (required == null) {
            out.add(Finding.warning(DOMAIN, "TURN_IN_AT_NO_GIVER",
                    "TurnInAt sends the player back to whoever offers this quest, but no Npc.ViewId says "
                            + "who that is, so the reward can never be collected; name the character or drop "
                            + "the leaf to collect it anywhere", id));
            return;
        }
        if (npcIds != null && !npcIds.declares(required)) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_TURN_IN_AT",
                    "TurnInAt names '" + required + "', which nothing on this server answers to; the reward "
                            + "cannot be collected until whichever mod stands that character up is installed",
                    id));
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

    /**
     * The {@code Requires} block, through the SHARED gate audit every domain carrying one uses, so a
     * lock is reported with the same code and the same wording wherever it was authored.
     */
    private static void validateRequires(@Nonnull QuestDefinition definition, @Nonnull QuestPool pool,
            @Nullable GateKindRegistry gateKinds, @Nonnull List<Finding> out) {
        out.addAll(GateValidator.validate(definition.requires(), DOMAIN, definition.id(), NOUN,
                gateKinds, null, prerequisite -> pool.definition(prerequisite) != null));
    }
}
