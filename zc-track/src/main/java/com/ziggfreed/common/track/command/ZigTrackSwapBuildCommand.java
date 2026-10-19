package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code swapbuild [--player=<name>] --track=<id> --slot=<n> [--force]}: swap an online player's picks on a track for
 * the saved build {@code n}, as the track's own page would ({@code PickService.swapLoadout}): the track's swap price
 * is taken (a swap to the build in use, which puts it back as saved, is free), a player in combat or in a boss fight
 * is refused, and the build is spent again by every rule a pick
 * obeys. With no saved build in use the swap would lose the picks in use, so it is refused until they are saved or
 * {@code --force} says to swap anyway. Left out, the sender is the player.
 */
final class ZigTrackSwapBuildCommand extends TrackTargetCommand {

    private final OptionalArg<Integer> slotArg;
    private final FlagArg forceArg;

    ZigTrackSwapBuildCommand() {
        super(ZigTrackCommand.SWAP_BUILD, false);
        this.slotArg = withOptionalArg("slot", TrackCommandMessages.desc("arg.slot"), ArgTypes.INTEGER);
        this.forceArg = withFlagArg("force", TrackCommandMessages.desc("arg.force"));
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
        PickService.Result result = PickService.swapLoadout(target.id(), def.id(), slot, forceArg.provided(ctx));
        if (result.done()) {
            TrackCommandMessages.done(ctx, "build.swapped", TrackText.name(def.asset()), target.name(), slot,
                    result.count());
            return;
        }
        BuildCommands.refused(ctx, result.status(), def, target);
    }
}
