package com.ziggfreed.common.objectives.title.command;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.ziggfreed.common.objectives.title.TitleUnlocks;

/**
 * {@code revoke --player=<name> --title=<id>}: take a title away (and stop showing it). The answer
 * says whether they had it, because "took it away" and "they never had it" are different facts.
 */
final class TitleRevokeCommand extends TitleTargetCommand {

    private final OptionalArg<String> titleArg;

    TitleRevokeCommand() {
        super(TitleCommandLine.REVOKE);
        this.titleArg = titleArg();
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String titleId = titleId(ctx, titleArg);
        if (titleId == null) {
            return;
        }
        String id = titleId.toLowerCase(Locale.ROOT);
        switch (TitleUnlocks.revoke(target.store(), target.ref(), target.playerRef(), id)) {
            case REVOKED -> TitleCommandMessages.done(ctx, "revoke.done", target.name(), id);
            case NOT_UNLOCKED -> TitleCommandMessages.detail(ctx, "revoke.absent", target.name(), id);
            case NO_RECORD -> TitleCommandMessages.refused(ctx, "player.no_record", target.name());
            default -> TitleCommandMessages.refused(ctx, "title.refused", id);
        }
    }
}
