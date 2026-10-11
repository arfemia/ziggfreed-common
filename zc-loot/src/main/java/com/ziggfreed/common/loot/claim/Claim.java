package com.ziggfreed.common.loot.claim;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.reward.InstanceReward;

/**
 * One payout a player has earned and not taken yet: what they see as a row on the Claims tab and take
 * with Claim. Rewards are collected, never handed over unasked (M428), so whatever pays a player a Claim
 * writes one of these to {@link ClaimStore} and the player presses Claim when their bags have room.
 *
 * @param id         what makes this payout this one: a second write of a Claim the player still holds is a
 *                   no-op, so a producer composes it from everything that makes the payout unique (the
 *                   source, the run, the reward, the player); matched without regard to case
 * @param source     where it came from, for logs and an owner's tools, conventionally {@code "<site>:<id>"}
 *                   ({@code "admin:<lootable>"})
 * @param titleKey   the lang key the row's title reads ({@code {0}, {1}, ...} bound from {@code titleArgs});
 *                   null reads as the tab's own word for a reward
 * @param titleArgs  the title's arguments, as raw text (data: an id, a number); a name that needs translating
 *                   belongs in the title's own key
 * @param difficulty the difficulty it was earned on, a lang key or a plain label; null for none
 * @param rewards    what pressing Claim hands over, block-first through the bag guard
 * @param createdAt  when it was written, epoch milliseconds
 */
public record Claim(@Nonnull String id, @Nonnull String source, @Nullable String titleKey,
                    @Nonnull List<String> titleArgs, @Nullable String difficulty,
                    @Nonnull List<InstanceReward> rewards, long createdAt) {

    public Claim {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a Claim needs an id");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("a Claim needs a source");
        }
        id = id.trim();
        source = source.trim();
        titleKey = titleKey == null || titleKey.isBlank() ? null : titleKey.trim();
        titleArgs = titleArgs == null ? List.of() : List.copyOf(titleArgs);
        difficulty = difficulty == null || difficulty.isBlank() ? null : difficulty.trim();
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
    }

    /** This Claim paying {@code rewards} instead, everything else kept (what did not fit, put back). */
    @Nonnull
    public Claim withRewards(@Nonnull List<InstanceReward> rewards) {
        return new Claim(id, source, titleKey, titleArgs, difficulty, rewards, createdAt);
    }

    /** Whether this Claim is {@code otherId}, without regard to case. */
    public boolean is(@Nullable String otherId) {
        return otherId != null && id.equalsIgnoreCase(otherId.trim());
    }
}
