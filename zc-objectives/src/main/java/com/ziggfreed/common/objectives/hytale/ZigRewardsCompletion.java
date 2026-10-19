package com.ziggfreed.common.objectives.hytale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.adventure.objectives.Objective;
import com.hypixel.hytale.builtin.adventure.objectives.completion.ObjectiveCompletion;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.objectives.interaction.InteractionRewards;

/**
 * A completed objective's {@link ZigRewardsCompletionAsset}: its rows, read as every authored payout outside a
 * quest reads them ({@link InteractionRewards#specs}), parked in the objective's one Claim for each participant
 * ({@link ObjectiveClaims}), which pays every CommonLib completion on the objective at once and only once.
 */
public final class ZigRewardsCompletion extends ObjectiveCompletion {

    public ZigRewardsCompletion(@Nonnull ZigRewardsCompletionAsset asset) {
        super(asset);
    }

    @Nonnull
    @Override
    public ZigRewardsCompletionAsset getAsset() {
        return (ZigRewardsCompletionAsset) super.getAsset();
    }

    @Override
    public void handle(@Nonnull Objective objective, @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ObjectiveClaims.park(objective, ZigRewardsCompletionAsset.TYPE);
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigRewardsCompletion{} " + super.toString();
    }
}
