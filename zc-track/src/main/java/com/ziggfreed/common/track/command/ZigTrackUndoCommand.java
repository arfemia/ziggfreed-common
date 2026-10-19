package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code undo [--player=<name>]}: undo an online player's latest pick still in its window (until the page it was
 * made on closes, at most {@code PickService.undoWindowMinutes()} after it was made): the slot reopens, or the take
 * is given back, and the grants are rebuilt. Left out, the sender is the player. The window's minutes ride the
 * refusal as a number; the command's description names none, since the engine's help binds no argument there.
 */
final class ZigTrackUndoCommand extends TrackTargetCommand {

    ZigTrackUndoCommand() {
        super(ZigTrackCommand.UNDO, false);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        PickService.Result result = PickService.undo(target.id());
        switch (result.status()) {
            case DONE -> {
                Message track = TrackText.nameOf(result.trackId());
                TrackCommandMessages.done(ctx, "undo.done", track == null ? Msg.raw(result.trackId()) : track,
                        target.name(), TrackText.pickName(result.options().isEmpty() ? result.pickId()
                                : result.options().get(0)));
            }
            case NOT_LIVE -> TrackCommandMessages.refused(ctx, "player.offline");
            case NEEDED -> TrackCommandMessages.refused(ctx, "undo.needed", target.name(),
                    TrackText.pickName(result.options().isEmpty() ? result.pickId() : result.options().get(0)));
            default -> TrackCommandMessages.detail(ctx, "undo.none", target.name(), PickService.undoWindowMinutes());
        }
    }
}
