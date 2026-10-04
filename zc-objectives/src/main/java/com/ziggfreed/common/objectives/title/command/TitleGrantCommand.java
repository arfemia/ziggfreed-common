package com.ziggfreed.common.objectives.title.command;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.ziggfreed.common.objectives.title.TitleConfig;
import com.ziggfreed.common.objectives.title.TitleUnlocks;

/**
 * {@code grant --player=<name> --title=<id>}: unlock a title through the write path the reward kind
 * uses. A title no loaded file defines is granted anyway, with a warning: the pack that ships it may
 * simply not have loaded, and refusing would turn a recoverable moment into a lost grant.
 */
final class TitleGrantCommand extends TitleTargetCommand {

    private final OptionalArg<String> titleArg;

    TitleGrantCommand() {
        super(TitleCommandLine.GRANT);
        this.titleArg = titleArg();
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String titleId = titleId(ctx, titleArg);
        if (titleId == null) {
            return;
        }
        String id = titleId.toLowerCase(Locale.ROOT);
        if (TitleConfig.getInstance().resolve(id) == null) {
            TitleCommandMessages.warned(ctx, "grant.unknown", id);
        }
        switch (TitleUnlocks.unlock(target.store(), target.ref(), target.playerRef(), id)) {
            case UNLOCKED -> TitleCommandMessages.done(ctx, "grant.done", target.name(), id);
            case ALREADY_UNLOCKED -> TitleCommandMessages.detail(ctx, "grant.already", target.name(), id);
            case NO_RECORD -> TitleCommandMessages.refused(ctx, "player.no_record", target.name());
            default -> TitleCommandMessages.refused(ctx, "title.refused", id);
        }
    }
}
