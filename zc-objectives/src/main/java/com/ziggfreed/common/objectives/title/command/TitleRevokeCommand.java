package com.ziggfreed.common.objectives.title.command;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.ziggfreed.common.objectives.title.TitleUnlocks;
import com.ziggfreed.common.objectives.title.ZigTitles;
import com.ziggfreed.common.objectives.title.command.TitlePlayers.Named;

/**
 * {@code revoke --player=<name> --title=<id>}: take a title away (and stop showing it). The answer
 * says whether they had it, because "took it away" and "they never had it" are different facts. A
 * player who is not online has the revoke queued for their next login.
 */
final class TitleRevokeCommand extends TitleTargetCommand {

    private final OptionalArg<String> titleArg;

    TitleRevokeCommand() {
        super(TitleCommandLine.REVOKE);
        this.titleArg = titleArg();
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String id = revocable(ctx);
        if (id != null) {
            answer(ctx, TitleUnlocks.revoke(target.store(), target.ref(), target.playerRef(), id), target.name(), id);
        }
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeOffline(@Nonnull CommandContext ctx, @Nonnull Named target) {
        String id = revocable(ctx);
        if (id == null) {
            return CompletableFuture.completedFuture(null);
        }
        return ZigTitles.revoke(target.id(), id).thenAccept(outcome -> answer(ctx, outcome, target.name(), id));
    }

    /** The lower-cased title to take away; null after a refusal. */
    @Nullable
    private String revocable(@Nonnull CommandContext ctx) {
        String titleId = titleId(ctx, titleArg);
        return titleId == null ? null : titleId.toLowerCase(Locale.ROOT);
    }

    private static void answer(@Nonnull CommandContext ctx, @Nonnull TitleUnlocks.Outcome outcome,
            @Nonnull String name, @Nonnull String id) {
        switch (outcome) {
            case REVOKED -> TitleCommandMessages.done(ctx, "revoke.done", name, id);
            case NOT_UNLOCKED -> TitleCommandMessages.detail(ctx, "revoke.absent", name, id);
            case QUEUED -> TitleCommandMessages.done(ctx, "revoke.queued", name, id);
            case NO_RECORD -> TitleCommandMessages.refused(ctx, "player.no_record", name);
            default -> TitleCommandMessages.refused(ctx, "title.refused", id);
        }
    }
}
