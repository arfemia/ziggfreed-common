package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code respend [--player=<name>] --track=<id>}: spend again, in order, the takes a Remember prestige put aside on
 * a track, as far as the points that have come back allow ({@code PickService.respendRemembered}), as the track's own
 * page would on the player's confirm. Left out, the sender is the player.
 */
final class ZigTrackRespendCommand extends TrackTargetCommand {

    ZigTrackRespendCommand() {
        super(ZigTrackCommand.RESPEND, false);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        PickService.Result result = PickService.respendRemembered(target.id(), def.id());
        switch (result.status()) {
            case DONE -> TrackCommandMessages.done(ctx, "respend.done", TrackText.name(def.asset()), target.name(),
                    result.count());
            case NOT_LIVE -> TrackCommandMessages.refused(ctx, "player.offline");
            default -> TrackCommandMessages.detail(ctx, "respend.none", TrackText.name(def.asset()), target.name());
        }
    }
}
