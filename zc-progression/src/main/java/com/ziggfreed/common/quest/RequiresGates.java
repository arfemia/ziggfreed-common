package com.ziggfreed.common.quest;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementGates;
import com.ziggfreed.common.achievement.FirstClaims;
import com.ziggfreed.common.achievement.UnlockOccasion;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * ONE gate for BOTH progression engines, reading the authored {@code Requires} block off the runtime
 * object itself.
 *
 * <p><b>Why one and not two.</b> A quest gate and an achievement gate ask the same question of the
 * same player against the same requirement model, so two implementations are the same duplication a
 * layer up: they would answer the same block two ways the first time either was fixed. Both engine
 * seams are small and neither names the other's noun, so one class implements both and the runtime
 * registers it twice.
 *
 * <p><b>It reads the runtime object, not a pool.</b> That is what lets it answer for content
 * authored in ANY format: whoever folded a quest or an achievement puts the block on the object,
 * and this never has to know which authoring layer it came from. A gate reading one pool could only
 * ever answer for the content in that pool, which is how a consumer ends up writing a second gate.
 *
 * <p><b>Silence about content that asks for nothing.</b> Under a shared runtime every registered
 * gate is asked about every piece of content, whoever authored it. Content carrying no block is
 * therefore not this gate's to refuse, and it says so by passing rather than by guessing.
 *
 * <p>Everything a consumer supplies beyond this - the factor vocabulary, the context a factor is
 * read against, its own requirement kinds, who answers a {@code Quests} prerequisite - lives on the
 * {@link GateEvaluator}.
 */
public final class RequiresGates implements QuestGates, AchievementGates {

    /**
     * The id a subject nobody could identify carries. A claim only one player may ever hold must
     * never be taken by one of those, or the first anonymous evaluation burns the achievement for
     * everybody.
     */
    private static final UUID NOBODY = new UUID(0L, 0L);

    private final GateEvaluator evaluator;

    private RequiresGates(@Nonnull GateEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** Gates that answer through {@code evaluator}. */
    @Nonnull
    public static RequiresGates of(@Nonnull GateEvaluator evaluator) {
        return new RequiresGates(evaluator);
    }

    /**
     * Who answers a {@code Quests} prerequisite, off a quest store's own records.
     *
     * <p><b>What counts as finished is the stored status {@link QuestStatus#COMPLETED}: the quest is
     * done AND its reward has been collected.</b> A quest still sitting in
     * {@link QuestStatus#COMPLETED_UNCLAIMED} - where a quest with {@code Claim} rewards
     * waits until the player takes their payout - does NOT satisfy a prerequisite. The
     * {@code ziggfreedcommon:quest_completed} factor reading answers with the same rule, so a
     * prerequisite written as a {@code Quests} leaf and the same requirement written as a factor
     * condition can never disagree about one player and one quest.
     *
     * <p>It reads the STORED status rather than the effective one, so a repeatable prerequisite
     * still counts as done while it sits on cooldown - "have you ever finished and collected this"
     * is the question a prerequisite asks.
     */
    @Nonnull
    public static GateEvaluator.CompletionProbe completionProbe(@Nonnull QuestProgressStore store) {
        return (subject, questId) -> store.status(subject, questId) == QuestStatus.COMPLETED;
    }

    /** The evaluator behind these gates, for a consumer that also wants to explain a refusal. */
    @Nonnull
    public GateEvaluator evaluator() {
        return evaluator;
    }

    /** The reason token for what shut this quest's gate, or null when it is open to the player. */
    @Nullable
    public String firstFailure(@Nonnull Subject subject, @Nonnull Quest quest) {
        return firstFailure(subject, quest.requires());
    }

    /** The reason token for what shut this achievement's gate, or null when nothing did. */
    @Nullable
    public String firstFailure(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        return firstFailure(subject, achievement.requires());
    }

    // ==================== quests ====================

    @Override
    public boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest,
            @Nonnull List<String> reasons) {
        String failure = firstFailure(subject, quest.requires());
        if (failure == null) {
            return true;
        }
        // The evaluator's own token, not a flat "prerequisites": a surface can then name the factor,
        // the permission or the quest that shut the gate rather than saying only that something did.
        reasons.add(failure);
        return false;
    }

    @Override
    public boolean prerequisitesMet(@Nonnull Subject subject, @Nonnull Quest quest) {
        return firstFailure(subject, quest.requires()) == null;
    }

    /**
     * Both accept-time questions off ONE reading of the block. The two questions this gate is asked
     * on accept - is the player past what the quest asks for first, and does the gate let them take
     * it - are the same requirement block read the same way, so reading it twice would cost a second
     * full evaluation for one decision on a path that runs per quest whenever a list renders.
     *
     * <p>The tokens are unchanged: a quest that requires its prerequisites contributes the flat
     * {@code prerequisites} token beside the evaluator's specific one, so a surface can say either
     * "not yet" or exactly what is missing.
     */
    @Override
    public boolean opensFor(@Nonnull Subject subject, @Nonnull Quest quest,
            @Nonnull List<String> reasons) {
        String failure = firstFailure(subject, quest.requires());
        if (failure == null) {
            return true;
        }
        if (quest.visibility().requirePrerequisites() && !reasons.contains(REASON_PREREQUISITES)) {
            reasons.add(REASON_PREREQUISITES);
        }
        reasons.add(failure);
        return false;
    }

    /**
     * Whether the payout can land now: is there anywhere for it to go?
     *
     * <p>Answering false parks the quest for manual collection instead of paying it out into
     * nowhere, and the player takes it once they have made room. A payout with no player behind it,
     * or one that reaches no inventory at all, is never blocked - a false answer always names
     * specific items that specifically will not fit.
     *
     * <p>Where a quest may be collected is a SEPARATE question the engine settles itself, off the
     * quest's own hand-in site; nothing here re-answers it.
     */
    @Override
    public boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Quest quest) {
        return LootRewardKinds.canAddAll(quest.rewards(), subject, "quest:" + quest.id());
    }

    // ==================== achievements ====================

    @Override
    public boolean canProgress(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        return firstFailure(subject, achievement.requires()) == null;
    }

    /**
     * Whether this subject may TAKE it. Only a server-first has anything to say: exactly one subject
     * wins the claim and the rest are refused, and a refusal deliberately leaves the criteria MET so
     * the decision can be revisited without anything being lost.
     *
     * <p>The claim is tested on every occasion, because a claim an admin has since cleared is one
     * this subject can now win and a sweep is where they find that out. The LOSS, though, is
     * announced only when the criteria were met in this very moment - the one time a subject
     * actually loses a race. Every later attempt re-discovers that same settled loss, and there are
     * many of them: the self-heal sweep runs on login, on every world change and whenever an
     * achievement surface opens, so a player standing on several taken claims was told about each of
     * them again every time they walked through a portal.
     *
     * <p>The loss is announced rather than handled here - what a player is told about it is
     * presentation, which this module cannot write.
     */
    @Override
    public boolean canUnlock(@Nonnull Subject subject, @Nonnull Achievement achievement,
            @Nonnull UnlockOccasion occasion) {
        if (!achievement.serverFirst()) {
            return true;
        }
        if (NOBODY.equals(subject.id())) {
            return false;
        }
        if (FirstClaims.store().tryClaim(achievement.id(), subject.id(), subject.name())) {
            return true;
        }
        if (occasion == UnlockOccasion.JUST_MET) {
            FirstClaims.fireLost(subject, achievement);
        }
        return false;
    }

    /**
     * Whether what is still owed can reach this subject now. It is the CLAIM rewards that are asked
     * about, because those are the ones this answer holds back: what lands on earning has already
     * been paid by the time anything asks.
     */
    @Override
    public boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        return LootRewardKinds.canAddAll(achievement.claimRewards(), subject,
                "achievement:" + achievement.id());
    }

    // ==================== internals ====================

    @Nullable
    private String firstFailure(@Nonnull Subject subject, @Nullable GateSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        return evaluator.firstFailure(subject, spec);
    }
}
