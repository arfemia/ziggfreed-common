package com.ziggfreed.common.party.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.party.ZcParty;
import com.ziggfreed.common.party.page.PartyInvitePage;

/**
 * {@code /zigparty} - the library's party, from the command line.
 *
 * <pre>
 * /zigparty                          open your party: its members, your invites, and who to invite
 * /zigparty invite --player=&lt;name&gt;   invite an online player
 * /zigparty accept                   accept your one party invite
 * /zigparty leave                    leave your party
 * </pre>
 *
 * <p>A PLAYER's family: it only acts for the caller, so it sits in the engine's adventurer group, which its
 * verbs inherit, and every player holds it. Bare, it opens the party page over {@link ZcParty}'s page deps; a
 * verb the line names runs instead (the engine matches a sub-command before running this body).
 */
public final class ZigPartyCommand extends AbstractAsyncCommand {

    /** The command family every party verb hangs off. */
    public static final String FAMILY = "zigparty";

    public ZigPartyCommand() {
        super(FAMILY, PartyCommandMessages.desc("family"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new PartyInviteCommand());
        addSubCommand(new PartyAcceptCommand());
        addSubCommand(new PartyLeaveCommand());
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        // A screen is the whole point of the bare command, and only a player has one.
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            PartyCommandMessages.refused(ctx, "needs_player");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> open(ctx, store, ref), world);
    }

    /** On the world thread: open the party page, or say it could not. */
    private static void open(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef viewer = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || viewer == null) {
            PartyCommandMessages.refused(ctx, "open.failed");
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new PartyInvitePage(viewer, ZcParty.pageDeps()));
    }
}
