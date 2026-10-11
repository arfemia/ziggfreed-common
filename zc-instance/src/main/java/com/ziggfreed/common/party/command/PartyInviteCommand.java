package com.ziggfreed.common.party.command;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.party.ZcParty;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;

/**
 * {@code invite --player=<name>}: invite an online player into the caller's party, making the caller a party of
 * their own first if they have none. A sent invite is announced by the party service to both players; only a
 * refusal is answered here.
 */
final class PartyInviteCommand extends AbstractTargetPlayerCommand<PlayerRef> {

    PartyInviteCommand() {
        super("invite", PartyCommandMessages.desc("invite"), PartyCommandMessages.desc("arg.player"),
                PartyCommandMessages::refused);
    }

    @Override
    @Nonnull
    protected PlayerRef buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return playerRef;
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull PlayerRef invitee) {
        if (!ctx.isPlayer()) {
            PartyCommandMessages.refused(ctx, "needs_player");
            return;
        }
        UUID target = invitee.getUuid();
        String name = PlayerDisplayNames.plainName(target, null);
        switch (ZcParty.service().invite(ctx.sender().getUuid(), target)) {
            case SENT -> {
                // The service tells the inviter and the invitee.
            }
            case SELF -> PartyCommandMessages.refused(ctx, "invite.self");
            case NOT_OWNER -> PartyCommandMessages.refused(ctx, "invite.not_owner");
            case PARTY_FULL -> PartyCommandMessages.refused(ctx, "invite.full");
            case TARGET_OFFLINE -> PartyCommandMessages.refused(ctx, "player.offline");
            case TARGET_IN_PARTY -> PartyCommandMessages.refused(ctx, "invite.in_party", name);
            case ALREADY_INVITED -> PartyCommandMessages.refused(ctx, "invite.already", name);
        }
    }
}
