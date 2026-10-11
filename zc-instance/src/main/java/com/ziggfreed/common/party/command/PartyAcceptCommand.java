package com.ziggfreed.common.party.command;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.party.PartyInvite;
import com.ziggfreed.common.party.PartyService;
import com.ziggfreed.common.party.ZcParty;

/**
 * {@code accept}: join the party whose invite the caller holds. With several invites waiting, the party page
 * ({@code /zigparty}) is where they pick one. A join is announced by the party service to the whole party, the new
 * member included; only a refusal is answered here.
 */
final class PartyAcceptCommand extends AbstractAsyncCommand {

    PartyAcceptCommand() {
        super("accept", PartyCommandMessages.desc("accept"));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        if (!ctx.isPlayer()) {
            PartyCommandMessages.refused(ctx, "needs_player");
            return CompletableFuture.completedFuture(null);
        }
        UUID self = ctx.sender().getUuid();
        PartyService parties = ZcParty.service();
        List<PartyInvite> pending = parties.pendingInvitesFor(self);
        if (pending.isEmpty()) {
            PartyCommandMessages.refused(ctx, "accept.none");
        } else if (pending.size() > 1) {
            PartyCommandMessages.said(ctx, "accept.several");
        } else {
            switch (parties.accept(self, pending.get(0).partyId())) {
                case JOINED -> {
                    // The service tells the whole party.
                }
                case NO_INVITE -> PartyCommandMessages.refused(ctx, "accept.none");
                case EXPIRED -> PartyCommandMessages.refused(ctx, "accept.expired");
                case PARTY_FULL -> PartyCommandMessages.refused(ctx, "accept.full");
                case ALREADY_IN_PARTY -> PartyCommandMessages.refused(ctx, "accept.in_party");
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
