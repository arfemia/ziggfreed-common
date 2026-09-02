package com.ziggfreed.common.achievement;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * The consumer's say over an achievement: whether a subject may make progress on it, may earn it,
 * may be paid for it, and may see it before earning it.
 *
 * <p>The engine owns MECHANICS - matching, counting, earning, paying, points. Everything that needs
 * to know about the game around it is a question asked here, so the engine never grows a switch on a
 * feature being installed, a class being chosen, or a race being won.
 *
 * <p>All four default to yes, so a consumer overrides only what it actually gates. Each is called on
 * the dispatch path, so keep them cheap; a gate that throws is reported and treated as a refusal.
 */
public interface AchievementGates {

    /** No gates at all - everything is progressable, earnable, payable, and visible. */
    AchievementGates OPEN = new AchievementGates() {
    };

    /**
     * May this subject make progress on this right now? Refusing freezes the counters without
     * touching what is already recorded, which is what a feature being switched off should do.
     */
    default boolean canProgress(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        return true;
    }

    /**
     * May this subject EARN it, now that everything is met? This is where a claim that only one
     * subject can win is arbitrated: refuse for whoever lost, and the criteria stay met so the
     * decision can be revisited without anything being lost.
     *
     * <p>The {@code occasion} says whether the criteria were met in this very moment or whether a
     * standing state is being re-tested. It never changes the DECISION - a gate that answers
     * differently on the two would hand an achievement out on a login it refused on the day - and it
     * is there for what a refusal is worth SAYING: a race lost once is re-discovered by every later
     * sweep, and only the first of those is news.
     */
    default boolean canUnlock(@Nonnull Subject subject, @Nonnull Achievement achievement,
            @Nonnull UnlockOccasion occasion) {
        return true;
    }

    /**
     * May the rewards reach this subject right now? Refusing parks the payout instead of dropping
     * it - the achievement stays waiting to be collected.
     */
    default boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        return true;
    }

    /**
     * May this subject SEE it before earning it? Asked on top of the achievement's own
     * {@code hidden} switch, for a listing that depends on the game around it. An achievement
     * already earned is always visible - somebody must be able to see what they have.
     */
    default boolean visible(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        return true;
    }
}
