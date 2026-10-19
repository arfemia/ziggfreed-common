package com.ziggfreed.common.objectives.hytale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.adventure.objectives.Objective;
import com.hypixel.hytale.builtin.adventure.objectives.completion.ObjectiveCompletion;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.TrackRewardKind;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.util.SafeLog;

/**
 * A completed objective's {@link TrackCompletionAsset}: one {@code Track} reward
 * ({@link TrackRewardKind#spec}), parked in the objective's one Claim for each participant
 * ({@link ObjectiveClaims}), which pays every CommonLib completion on the objective at once and only once. A
 * track this server does not run, or no amount, pays nothing and says so in the log.
 */
public final class TrackCompletion extends ObjectiveCompletion {

    public TrackCompletion(@Nonnull TrackCompletionAsset asset) {
        super(asset);
    }

    @Nonnull
    @Override
    public TrackCompletionAsset getAsset() {
        return (TrackCompletionAsset) super.getAsset();
    }

    @Override
    public void handle(@Nonnull Objective objective, @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TrackCompletionAsset asset = getAsset();
        if (TrackService.def(asset.trackId()) == null || asset.amount() == 0) {
            SafeLog.info("[objectives] " + TrackCompletionAsset.TYPE + " on objective '" + objective.getObjectiveId()
                    + "': no usable track or no amount, nothing to pay");
            return;
        }
        ObjectiveClaims.park(objective, TrackCompletionAsset.TYPE);
    }

    @Nonnull
    @Override
    public String toString() {
        return "TrackCompletion{} " + super.toString();
    }
}
