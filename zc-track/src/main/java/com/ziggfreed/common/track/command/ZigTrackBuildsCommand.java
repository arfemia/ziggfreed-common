package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code builds [--player=<name>] --track=<id>}: list an online player's saved builds on a track
 * ({@code PickService.loadouts}): each slot, how many picks it holds, and the one in use. Left out, the sender is the
 * player.
 */
final class ZigTrackBuildsCommand extends TrackTargetCommand {

    ZigTrackBuildsCommand() {
        super(ZigTrackCommand.BUILDS, false);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        PickService.Loadouts builds = PickService.loadouts(target.id(), def.id());
        if (builds.max() <= 0) {
            TrackCommandMessages.detail(ctx, "build.none", TrackText.name(def.asset()));
            return;
        }
        TrackCommandMessages.detail(ctx, "build.head", TrackText.name(def.asset()), target.name(), builds.max());
        for (PickService.LoadoutView slot : builds.slots()) {
            String key = !slot.saved() ? "build.line.empty" : slot.active() ? "build.line.active" : "build.line";
            TrackCommandMessages.detail(ctx, key, slot.slot(), slot.picks());
        }
    }
}
