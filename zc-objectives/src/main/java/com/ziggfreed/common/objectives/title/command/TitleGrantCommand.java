package com.ziggfreed.common.objectives.title.command;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.ziggfreed.common.objectives.title.TitleConfig;
import com.ziggfreed.common.objectives.title.TitleUnlocks;
import com.ziggfreed.common.objectives.title.ZigTitles;
import com.ziggfreed.common.objectives.title.command.TitlePlayers.Named;

/**
 * {@code grant --player=<name> --title=<id>}: unlock a title through the write path the reward kind
 * uses. A title no loaded file defines is granted anyway, with a warning: the pack that ships it may
 * simply not have loaded, and refusing would turn a recoverable moment into a lost grant. A player
 * who is not online has the grant queued for their next login.
 */
final class TitleGrantCommand extends TitleTargetCommand {

    private final OptionalArg<String> titleArg;

    TitleGrantCommand() {
        super(TitleCommandLine.GRANT);
        this.titleArg = titleArg();
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String id = grantable(ctx);
        if (id != null) {
            answer(ctx, TitleUnlocks.unlock(target.store(), target.ref(), target.playerRef(), id), target.name(), id);
        }
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeOffline(@Nonnull CommandContext ctx, @Nonnull Named target) {
        String id = grantable(ctx);
        if (id == null) {
            return CompletableFuture.completedFuture(null);
        }
        return ZigTitles.grant(target.id(), id).thenAccept(outcome -> answer(ctx, outcome, target.name(), id));
    }

    /** The lower-cased title to grant, warning when nothing loaded defines it; null after a refusal. */
    @Nullable
    private String grantable(@Nonnull CommandContext ctx) {
        String titleId = titleId(ctx, titleArg);
        if (titleId == null) {
            return null;
        }
        String id = titleId.toLowerCase(Locale.ROOT);
        if (TitleConfig.getInstance().resolve(id) == null) {
            TitleCommandMessages.warned(ctx, "grant.unknown", id);
        }
        return id;
    }

    private static void answer(@Nonnull CommandContext ctx, @Nonnull TitleUnlocks.Outcome outcome,
            @Nonnull String name, @Nonnull String id) {
        switch (outcome) {
            case UNLOCKED -> TitleCommandMessages.done(ctx, "grant.done", name, id);
            case ALREADY_UNLOCKED -> TitleCommandMessages.detail(ctx, "grant.already", name, id);
            case QUEUED -> TitleCommandMessages.done(ctx, "grant.queued", name, id);
            case NO_RECORD -> TitleCommandMessages.refused(ctx, "player.no_record", name);
            default -> TitleCommandMessages.refused(ctx, "title.refused", id);
        }
    }
}
