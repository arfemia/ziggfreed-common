package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.track.pick.TrackPickRespecAsset;
import com.ziggfreed.common.track.pick.TrackPickRespecConfig;

/**
 * {@code respec [--player=<name>] --track=<id>}: respec an online player's picks on a track, for free (an admin's
 * respec; a player's own is a trainer's service with its price): only what a trainer taught stays, every other
 * pick (the Mastery row included) is cleared, every slot reopens with the same offer it
 * had, every point spent comes back, and the grants are rebuilt. Left out, the sender is the player.
 *
 * <p>{@code respec [--player=<name>] --kind=<respec>}: run a respec of one kind of pick (a {@code TrackPickRespecs}
 * file) for the player instead, on every track, free and anywhere ({@code PickService.respecKindAsAdmin}), giving back
 * what the file says.
 */
final class ZigTrackRespecCommand extends TrackTargetCommand {

    private final OptionalArg<String> kindArg;

    ZigTrackRespecCommand() {
        super(ZigTrackCommand.RESPEC, false);
        this.kindArg = withOptionalArg("kind", TrackCommandMessages.desc("arg.kind"), ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        String kind = typed(ctx, kindArg);
        if (kind != null) {
            respecKind(ctx, target, kind);
            return;
        }
        if (typedTrack(ctx) == null) {
            TrackCommandMessages.refused(ctx, "respec.needed");
            return;
        }
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

    /** The {@code --kind} form: the respec file {@code kind} names, run for {@code target} as an admin's. */
    private static void respecKind(@Nonnull CommandContext ctx, @Nonnull TrackTarget target, @Nonnull String kind) {
        TrackPickRespecAsset respec = TrackPickRespecConfig.getInstance().live(kind);
        if (respec == null) {
            TrackCommandMessages.refused(ctx, "respec.kind.unknown", kind);
            return;
        }
        PickService.Result result = PickService.respecKindAsAdmin(target.id(), respec.getId());
        switch (result.status()) {
            case DONE -> TrackCommandMessages.done(ctx, "respec.kind.done", TrackText.respecName(respec),
                    target.name(), result.count());
            case NOT_LIVE -> TrackCommandMessages.refused(ctx, "player.offline");
            default -> TrackCommandMessages.detail(ctx, "respec.kind.none", TrackText.respecName(respec),
                    target.name());
        }
    }
}
