package com.ziggfreed.common.objectives.flair;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;

/**
 * {@code revoke --player=<name> --flair=<id>}: take a flair away from a player. The answer says
 * whether they had it, because "took it away" and "they never had it" are two different facts an
 * administrator cleaning up after a mis-grant wants to know.
 */
final class FlairRevokeCommand extends FlairTargetCommand {

    private final OptionalArg<String> flairArg;

    FlairRevokeCommand() {
        super(FlairCommandLine.REVOKE);
        this.flairArg = flairArg();
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String flairId = flairId(ctx, flairArg);
        if (flairId == null) {
            return;
        }
        String id = flairId.toLowerCase(Locale.ROOT);
        switch (FlairUnlocks.revoke(target.store(), target.ref(), target.playerRef(), id)) {
            case REVOKED -> FlairAdminMessages.done(ctx, "revoke.done", target.name(), id);
            case NOT_UNLOCKED -> FlairAdminMessages.detail(ctx, "revoke.absent", target.name(), id);
            case NO_RECORD -> FlairAdminMessages.refused(ctx, "player.no_record", target.name());
            default -> FlairAdminMessages.refused(ctx, "flair.refused", id);
        }
    }
}
