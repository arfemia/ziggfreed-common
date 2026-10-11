package com.ziggfreed.common.party.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.party.ZcParty;

/**
 * {@code leave}: leave the caller's party. The party service tells those left behind (and who leads now, when the
 * leader left); an emptied party is gone.
 */
final class PartyLeaveCommand extends AbstractAsyncCommand {

    PartyLeaveCommand() {
        super("leave", PartyCommandMessages.desc("leave"));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        if (!ctx.isPlayer()) {
            PartyCommandMessages.refused(ctx, "needs_player");
        } else if (ZcParty.service().leave(ctx.sender().getUuid())) {
            PartyCommandMessages.said(ctx, "leave.done");
        } else {
            PartyCommandMessages.refused(ctx, "leave.none");
        }
        return CompletableFuture.completedFuture(null);
    }
}
