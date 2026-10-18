package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;

/**
 * {@code prestige [--player=<name>] --track=<id>}: start an online player's climb again, as the track's own
 * prestige allows ({@code TrackService.prestige}): the value back to the start, the best level kept, the count up
 * one. Refused when the track does not prestige, every rank is taken, or the climb has not reached the level the
 * next rank needs. Left out, the sender is the player.
 */
final class ZigTrackPrestigeCommand extends TrackTargetCommand {

    ZigTrackPrestigeCommand() {
        super(ZigTrackCommand.PRESTIGE, false);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        TrackKey key = TrackKey.player(target.id(), def.asset().getId());
        TrackService.Result result = TrackService.prestige(key, ZigTrackCommand.PRESTIGE);
        switch (result.status()) {
            case CHANGED -> TrackCommandMessages.done(ctx, "prestige.done", TrackText.name(def.asset()),
                    target.name(), result.change() == null ? 1 : result.change().after().prestige());
            case NOT_READY -> {
                TrackReading reading = TrackService.read(key);
                int done = reading == null ? 0 : reading.prestige();
                TrackCommandMessages.refused(ctx, "prestige.not_ready", TrackText.name(def.asset()), target.name(),
                        def.asset().prestigeLevel(done + 1));
            }
            case NOT_ALLOWED -> TrackCommandMessages.refused(ctx, "prestige.not_allowed", TrackText.name(def.asset()),
                    target.name());
            case NOT_LIVE -> TrackCommandMessages.refused(ctx, "player.offline");
            default -> TrackCommandMessages.refused(ctx, "track.unknown", def.asset().getId());
        }
    }
}
