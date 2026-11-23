package com.ziggfreed.common.track.command;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code starter [--player=<name>] --track=<id> [--points=<n>]}: spend what is left of a track's suggested build
 * ({@code Starter}) for an online player, in order, as far as their points allow ({@code PickService.starterLeft}, then
 * {@code PickService.spendInOrder}), as a trainer's suggested build would on the player's confirm: one undo, never a
 * priced pick. With {@code --points} only the build's first that many points are read, so a line written once for
 * the points a player had earned (a one-time Claim) never spends the points they earn after it, and run again spends
 * nothing more. Left out, the sender is the player.
 */
final class ZigTrackStarterCommand extends TrackTargetCommand {

    private final OptionalArg<Integer> pointsArg;

    ZigTrackStarterCommand() {
        super(ZigTrackCommand.STARTER, false);
        this.pointsArg = withOptionalArg("points", TrackCommandMessages.desc("arg.points"), ArgTypes.INTEGER);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        Integer points = pointsArg.provided(ctx) ? pointsArg.get(ctx) : null;
        List<String> left = PickService.starterLeft(target.id(), def.id(),
                points == null ? Integer.MAX_VALUE : points);
        PickService.Result result = left.isEmpty() ? null : PickService.spendInOrder(target.id(), def.id(), left);
        if (result != null && result.done()) {
            TrackCommandMessages.done(ctx, "starter.done", TrackText.name(def.asset()), target.name(),
                    result.count());
        } else if (result != null && result.status() == PickService.Status.NOT_LIVE) {
            TrackCommandMessages.refused(ctx, "player.offline");
        } else {
            TrackCommandMessages.detail(ctx, "starter.none", TrackText.name(def.asset()), target.name());
        }
    }
}
