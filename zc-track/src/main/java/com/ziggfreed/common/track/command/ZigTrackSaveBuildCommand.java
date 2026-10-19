package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code savebuild [--player=<name>] --track=<id> --slot=<n>}: save an online player's picks in use on a track as
 * the saved build {@code n} (1 to the track's {@code Loadouts.Max}), as the track's own page would
 * ({@code PickService.saveLoadout}). Free. Left out, the sender is the player.
 */
final class ZigTrackSaveBuildCommand extends TrackTargetCommand {

    private final OptionalArg<Integer> slotArg;

    ZigTrackSaveBuildCommand() {
        super(ZigTrackCommand.SAVE_BUILD, false);
        this.slotArg = withOptionalArg("slot", TrackCommandMessages.desc("arg.slot"), ArgTypes.INTEGER);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        Integer slot = BuildCommands.slot(ctx, slotArg);
        if (slot == null) {
            return;
        }
        PickService.Result result = PickService.saveLoadout(target.id(), def.id(), slot);
        if (result.done()) {
            TrackCommandMessages.done(ctx, "build.saved", TrackText.name(def.asset()), target.name(), slot,
                    result.count());
            return;
        }
        BuildCommands.refused(ctx, result.status(), def, target);
    }
}
