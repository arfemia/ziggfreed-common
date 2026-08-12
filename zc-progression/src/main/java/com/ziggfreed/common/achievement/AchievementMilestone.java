package com.ziggfreed.common.achievement;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * A reward for reaching a POINTS TOTAL rather than for any one achievement: earn enough anywhere and
 * this pays out.
 *
 * <p>Its two reward lists mean exactly what an achievement's do - {@link #autoRewards()} land the
 * moment the threshold is crossed, {@link #claimRewards()} wait to be collected - so a milestone
 * with nothing to collect settles in one step.
 *
 * <p>Milestones are state, not moments: they are recomputed whenever a total changes and a consumer
 * reads their status to render them. The moment worth reacting to is the achievement whose earning
 * crossed the threshold, and that already publishes an event of its own.
 *
 * @param threshold    the points total that unlocks it
 * @param autoRewards  paid the instant the total is reached
 * @param claimRewards paid when the subject collects
 */
public record AchievementMilestone(int threshold, @Nonnull List<RewardSpec> autoRewards,
                                   @Nonnull List<RewardSpec> claimRewards) {

    public AchievementMilestone {
        threshold = Math.max(0, threshold);
        autoRewards = List.copyOf(autoRewards);
        claimRewards = List.copyOf(claimRewards);
    }

    /** A milestone that pays out the moment it is reached. */
    @Nonnull
    public static AchievementMilestone auto(int threshold, @Nonnull List<RewardSpec> rewards) {
        return new AchievementMilestone(threshold, rewards, List.of());
    }

    /** A milestone whose reward waits to be collected. */
    @Nonnull
    public static AchievementMilestone claimable(int threshold, @Nonnull List<RewardSpec> rewards) {
        return new AchievementMilestone(threshold, List.of(), rewards);
    }

    /** True when something is left to collect after the threshold is reached. */
    public boolean requiresClaim() {
        return !claimRewards.isEmpty();
    }
}
