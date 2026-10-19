package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code respec [--player=<name>] --track=<id>}: respec an online player's picks on a track, for free (an admin's
 * respec; a player's own is a trainer's service with its price): only what a trainer taught stays, every other
 * pick (Mastery and {@code KeptThroughPrestige} ones included) is cleared, every slot reopens with the same offer it
 * had, every point spent comes back, and the grants are rebuilt. Left out, the sender is the player.
 */
final class ZigTrackRespecCommand extends TrackTargetCommand {

    ZigTrackRespecCommand() {
        super(ZigTrackCommand.RESPEC, false);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        PickService.Result result = PickService.respec(target.id(), def.id());
        switch (result.status()) {
            case DONE -> TrackCommandMessages.done(ctx, "respec.done", TrackText.name(def.asset()), target.name(),
                    result.count());
            case NOT_LIVE -> TrackCommandMessages.refused(ctx, "player.offline");
            default -> TrackCommandMessages.detail(ctx, "respec.none", TrackText.name(def.asset()), target.name());
        }
    }
}
