package com.ziggfreed.common.instance.claims;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootFactors;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.subject.Subject;

/**
 * The one roll a payout that writes a Claim from a loot table makes (a dungeon's clear, a reward chest's
 * {@code Contents}, {@code /zigclaims give}): the table decided once ({@code LootEngine.select}) as a won run with no
 * score ({@code LootFactors.lookupFor(0, true)}, so a table gated on a win pays its floor), kept as rewards to hand over
 * later ({@link DeferredRewards}), each command reward keeping {@code {player}} for whoever claims it, and repeated picks
 * of one item read as one reward. The Claim pays exactly that when it is taken.
 */
public final class ClaimRolls {

    /** The name a deferred command keeps for whoever presses Claim. */
    public static final String CLAIMER = "{player}";

    private ClaimRolls() {
    }

    /**
     * What one pass of {@code table} decides for {@code player}, as rewards kept for later, under {@code source}; a
     * reward that cannot be kept is reported to {@code warn}. Empty when the roll lands nothing.
     */
    @Nonnull
    public static List<InstanceReward> roll(@Nonnull LootableAsset table, @Nonnull UUID player, @Nonnull String source,
            @Nonnull Consumer<String> warn) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<LootEngine.Selected> decided = LootEngine.select(table.rollsOrEmpty(), table.poolOrEmpty(), null,
                LootFactors.lookupFor(0, true), random::nextDouble);
        return InstanceReward.merge(DeferredRewards.fromSelection(decided, RewardKinds.shared(),
                Subject.of(player, CLAIMER), source, warn));
    }
}
