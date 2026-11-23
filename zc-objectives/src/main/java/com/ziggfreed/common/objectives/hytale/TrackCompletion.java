package com.ziggfreed.common.objectives.hytale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.adventure.objectives.Objective;
import com.hypixel.hytale.builtin.adventure.objectives.completion.ObjectiveCompletion;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackRewardKind;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.util.SafeLog;

/**
 * A completed objective's {@link TrackCompletionAsset}: one {@code Track} reward, a fixed amount or minutes of play
 * priced when the Claim is written ({@link TrackRewardKind#of}), parked in the objective's one Claim for each
 * participant ({@link ObjectiveClaims}), which pays every CommonLib completion on the objective at once and only once.
 * A track this server does not run, or a completion paying nothing (no price and no amount), pays nothing and says so
 * in the log.
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
        TrackDef def = TrackService.def(asset.trackId());
        if (def == null || asset.rewardOn(def.asset().getId()) == null) {
            SafeLog.info("[objectives] " + TrackCompletionAsset.TYPE + " on objective '" + objective.getObjectiveId()
                    + "': no usable track, or no price and no amount, nothing to pay");
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
